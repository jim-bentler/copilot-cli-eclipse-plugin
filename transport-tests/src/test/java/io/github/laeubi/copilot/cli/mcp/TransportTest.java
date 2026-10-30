/*******************************************************************************
 * Copyright (c) 2026 Siemens AG.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.eclipse.core.runtime.ILog;

class TransportTest {
	private static final ILog LOG = ILog.class.cast(java.lang.reflect.Proxy.newProxyInstance(
			ILog.class.getClassLoader(), new Class<?>[] { ILog.class }, (proxy, method, args) -> null));
	private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory());

	@AfterEach
	void cleanupWorkers() {
		workers.shutdownNow();
	}

	private <T> Future<T> async(Callable<T> work) {
		return workers.submit(work);
	}

	private static <T> T result(Future<T> work) throws Exception {
		return work.get(5, TimeUnit.SECONDS);
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Condition did not become true");
			Thread.sleep(5);
		}
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = (owner instanceof McpServer ? McpServer.class : owner.getClass()).getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static int connectionCount(McpServer server) {
		synchronized (server) {
			try {
				return ((Set<?>) field(server, "connections")).size();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
	}

	private static String pipeName() {
		return "\\\\.\\pipe\\copilot-transport-test-" + UUID.randomUUID();
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeCancelsAccept() throws Exception {
		NamedPipe pipe = new NamedPipe(pipeName());
		Object handle = field(pipe, "pending");
		Future<Boolean> accept = async(() -> {
			assertThrows(IOException.class, pipe::accept);
			return true;
		});
		try {
			await(() -> operationCount(handle) == 1);
		} finally {
			pipe.close();
		}
		assertTrue(result(accept));
		assertHandleClosed(handle);
	}

	private static int operationCount(Object handle) {
		synchronized (handle) {
			try {
				return (int) field(handle, "operations");
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
	}

	private static void assertHandleClosed(Object handle) throws Exception {
		assertEquals(0, operationCount(handle));
		try (Arena arena = Arena.ofConfined()) {
			var lookup = SymbolLookup.libraryLookup("kernel32", arena);
			// A closed handle value may already have been reused for a non-pipe resource.
			var info = Linker.nativeLinker().downcallHandle(lookup.find("GetNamedPipeInfo").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			try {
				int success = (int) info.invokeExact((MemorySegment) field(handle, "value"),
						MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
				assertEquals(0, success, "Native pipe handle remains open");
			} catch (Throwable t) {
				throw new AssertionError(t);
			}
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeCancelsConcurrentReadAndWrite() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			Future<Boolean> read = async(() -> {
				try {
					connection.in().read();
				} catch (IOException expected) {
					assertFalse(connection.isOpen());
				}
				return true;
			});
			Future<Boolean> write = async(() -> {
				assertThrows(IOException.class, () -> connection.out().write(new byte[8 * 1024 * 1024]));
				return true;
			});
			await(() -> operationCount(handle) == 2);
			connection.close();
			assertTrue(result(read));
			assertTrue(result(write));
			assertHandleClosed(handle);
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writesCompleteWhileReadIsPending() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try (RandomAccessFile client = new RandomAccessFile(name, "rw");
				McpServer.Connection connection = result(accept)) {
			Object handle = field(connection, "handle");
			Future<Integer> read = async(() -> connection.in().read());
			await(() -> operationCount(handle) == 1);
			result(async(() -> {
				connection.out().write(42);
				return true;
			}));
			int received = result(async(client::read));
			assertEquals(42, received);
			client.write(17);
			assertEquals(17, result(read));
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void writeFailureClosesHandle() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Future<McpServer.Connection> accept = async(pipe::accept);
		try {
			RandomAccessFile client = new RandomAccessFile(name, "rw");
			try (McpServer.Connection connection = result(accept)) {
				Object handle = field(connection, "handle");
				client.close();
				assertThrows(IOException.class, () -> connection.out().write(1));
				assertFalse(connection.isOpen());
				assertHandleClosed(handle);
			} finally {
				client.close();
			}
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void replacementFailureClosesConnectedInstance() throws Exception {
		String name = pipeName();
		NamedPipe pipe = new NamedPipe(name);
		Object handle = field(pipe, "pending");
		Field nameField = NamedPipe.class.getDeclaredField("name");
		nameField.setAccessible(true);
		nameField.set(pipe, "not-a-valid-pipe-name");
		Future<Boolean> accept = async(() -> {
			assertThrows(IOException.class, pipe::accept);
			return true;
		});
		try (RandomAccessFile client = new RandomAccessFile(name, "rw")) {
			assertTrue(result(accept));
			assertHandleClosed(handle);
			assertNull(field(pipe, "pending"));
		} finally {
			pipe.close();
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void closeRacingOperationStartNeverUsesClosedHandle() throws Exception {
		for (int i = 0; i < 50; i++) {
			String name = pipeName();
			NamedPipe pipe = new NamedPipe(name);
			Future<McpServer.Connection> accept = async(pipe::accept);
			try (RandomAccessFile client = new RandomAccessFile(name, "rw");
					McpServer.Connection connection = result(accept)) {
				Object handle = field(connection, "handle");
				Future<Boolean> read = async(() -> {
					try {
						connection.in().read();
					} catch (IOException expected) {
						assertFalse(connection.isOpen());
					}
					return true;
				});
				connection.close();
				assertTrue(result(read));
				assertHandleClosed(handle);
			} finally {
				pipe.close();
			}
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void failedUnixBindReleasesListener() throws Exception {
		Path directory = Files.createTempDirectory("mcp-bind-");
		McpServer server = new McpServer(directory.resolve("x".repeat(200)), null, "test", (tool, args) -> null, LOG);
		try {
			assertThrows(IOException.class, server::start);
			assertFalse(server.isRunning());
			assertNull(field(server, "listener"));
		} finally {
			server.stop();
			Files.delete(directory);
		}
	}

	@Test
	void shutdownClosesIdleAndIncompleteRequests() throws Exception {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + UUID.randomUUID() + ".sock");
		String name = pipeName();
		McpServer server = new McpServer(windows ? null : path, windows ? name : null, "test",
				(tool, args) -> null, LOG);
		server.start();
		try (var idle = connect(windows, name, path)) {
			await(() -> connectionCount(server) == 1);
			try (var partial = connect(windows, name, path)) {
				partial.out().write("POST /mcp HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
				partial.out().flush();
				await(() -> connectionCount(server) == 2);
				server.stop();
				assertTrue(result(async(() -> disconnected(idle.in()))));
				assertTrue(result(async(() -> disconnected(partial.in()))));
				await(() -> connectionCount(server) == 0);
				await(() -> Thread.getAllStackTraces().keySet().stream()
						.noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("MCP #")));
			}
		} finally {
			server.stop();
		}
	}

	@Test
	void completedHandlerUnregistersConnection() throws Exception {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		Path path = Path.of(System.getProperty("java.io.tmpdir"), "mcp-" + UUID.randomUUID() + ".sock");
		String name = pipeName();
		McpServer server = new McpServer(windows ? null : path, windows ? name : null, "test",
				(tool, args) -> null, LOG);
		server.start();
		try (var client = connect(windows, name, path)) {
			await(() -> connectionCount(server) == 1);
			client.out().write("GET /mcp HTTP/1.1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			client.out().flush();
			String response = result(async(() -> new String(client.in().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8)));
			assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"));
			await(() -> connectionCount(server) == 0);
		} finally {
			server.stop();
		}
	}

	private static boolean disconnected(InputStream in) throws IOException {
		try {
			return in.read() == -1;
		} catch (IOException expected) {
			return true;
		}
	}

	private static McpServer.Connection connect(boolean windows, String name, Path path) throws IOException {
		if (windows) {
			RandomAccessFile file = new RandomAccessFile(name, "rw");
			return new McpServer.Connection() {
				public InputStream in() { return java.nio.channels.Channels.newInputStream(file.getChannel()); }
				public OutputStream out() { return java.nio.channels.Channels.newOutputStream(file.getChannel()); }
				public boolean isOpen() { return file.getChannel().isOpen(); }
				public void close() throws IOException { file.close(); }
			};
		}
		SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			socket.connect(UnixDomainSocketAddress.of(path));
		} catch (IOException | RuntimeException e) {
			socket.close();
			throw e;
		}
		return new McpServer.Connection() {
			public InputStream in() { return java.nio.channels.Channels.newInputStream(socket); }
			public OutputStream out() { return java.nio.channels.Channels.newOutputStream(socket); }
			public boolean isOpen() { return socket.isOpen(); }
			public void close() throws IOException { socket.close(); }
		};
	}

	@Test
	void workerStartupFailureClosesAcceptedConnection() throws Exception {
		McpServer server = new McpServer(Path.of("unused"), null, "test", (tool, args) -> null, LOG) {
			@Override
			void startConnectionThread(Runnable handler, long connId) {
				throw new OutOfMemoryError("Simulated worker startup failure");
			}
		};
		assertDispatchCloses(server, true);
	}

	@Test
	void lateAcceptedConnectionIsClosedAfterShutdown() throws Exception {
		McpServer server = new McpServer(Path.of("unused"), null, "test", (tool, args) -> null, LOG);
		assertDispatchCloses(server, false);
	}

	private static void assertDispatchCloses(McpServer server, boolean running) throws Exception {
		((java.util.concurrent.atomic.AtomicBoolean) field(server, "running")).set(running);
		boolean[] closed = { false };
		McpServer.Connection client = new McpServer.Connection() {
			public InputStream in() { return InputStream.nullInputStream(); }
			public OutputStream out() { return OutputStream.nullOutputStream(); }
			public boolean isOpen() { return !closed[0]; }
			public void close() { closed[0] = true; }
		};
		Class<?> listener = Class.forName(McpServer.class.getName() + "$Listener");
		Method dispatch = McpServer.class.getDeclaredMethod("dispatchConnection", McpServer.Connection.class, listener);
		dispatch.setAccessible(true);
		if (running) {
			var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
					() -> dispatch.invoke(server, client, null));
			assertInstanceOf(OutOfMemoryError.class, thrown.getCause());
		} else {
			dispatch.invoke(server, client, null);
		}
		assertTrue(closed[0]);
		assertEquals(0, connectionCount(server));
	}
}
