/*******************************************************************************
 * Copyright (c) 2026 Siemens AG.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.github.laeubi.copilot.cli.mcp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows named pipe server built on the Foreign Function &amp; Memory API.
 * <p>
 * Copilot CLI (Node.js) treats the lock file's {@code socketPath} as a named
 * pipe on Windows, which Java cannot listen on through {@code java.nio}.
 * <p>
 * Handles are opened for overlapped I/O: on a synchronous handle Windows
 * serialises all I/O, so an SSE push would wait behind a pending read.
 */
final class NamedPipe {

	private static final int PIPE_ACCESS_DUPLEX = 0x3;
	private static final int FILE_FLAG_OVERLAPPED = 0x40000000;
	private static final int PIPE_TYPE_BYTE = 0x0;
	private static final int PIPE_WAIT = 0x0;
	private static final int PIPE_REJECT_REMOTE_CLIENTS = 0x8;
	private static final int PIPE_UNLIMITED_INSTANCES = 255;
	private static final int BUFFER_SIZE = 64 * 1024;
	private static final int ERROR_BROKEN_PIPE = 109;
	private static final int ERROR_PIPE_NOT_CONNECTED = 233;
	private static final int ERROR_PIPE_CONNECTED = 535;
	private static final int ERROR_OPERATION_ABORTED = 995;
	private static final int ERROR_IO_PENDING = 997;
	private static final long OVERLAPPED_SIZE = 32;
	private static final long OVERLAPPED_EVENT = 24;
	private static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

	private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
	private static final VarHandle LAST_ERROR = CALL_STATE
			.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

	private static final MethodHandle CREATE_NAMED_PIPE;
	private static final MethodHandle CONNECT_NAMED_PIPE;
	private static final MethodHandle READ_FILE;
	private static final MethodHandle WRITE_FILE;
	private static final MethodHandle CREATE_EVENT;
	private static final MethodHandle GET_OVERLAPPED_RESULT;
	private static final MethodHandle CANCEL_IO_EX;
	private static final MethodHandle CLOSE_HANDLE;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
		CREATE_NAMED_PIPE = linker.downcallHandle(kernel32.find("CreateNamedPipeW").orElseThrow(),
				FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
						ADDRESS),
				lastError);
		CONNECT_NAMED_PIPE = linker.downcallHandle(kernel32.find("ConnectNamedPipe").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), lastError);
		READ_FILE = linker.downcallHandle(kernel32.find("ReadFile").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), lastError);
		WRITE_FILE = linker.downcallHandle(kernel32.find("WriteFile").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), lastError);
		CREATE_EVENT = linker.downcallHandle(kernel32.find("CreateEventW").orElseThrow(),
				FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), lastError);
		GET_OVERLAPPED_RESULT = linker.downcallHandle(kernel32.find("GetOverlappedResult").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT), lastError);
		CANCEL_IO_EX = linker.downcallHandle(kernel32.find("CancelIoEx").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		CLOSE_HANDLE = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS));
	}

	private final String name;
	private boolean open = true;
	private PipeHandle pending;

	NamedPipe(String name) throws IOException {
		this.name = name;
		this.pending = createInstance();
	}

	String name() {
		return name;
	}

	/**
	 * Blocks until a client connects and returns that connection. A fresh
	 * instance is created before returning, so the pipe never disappears
	 * between clients.
	 */
	McpServer.Connection accept() throws IOException {
		PipeHandle handle;
		synchronized (this) {
			if (!open) {
				throw new IOException("Pipe closed");
			}
			if (pending == null) {
				pending = createInstance();
			}
			handle = pending;
		}
		boolean transferred = false;
		try {
			int result = overlapped(handle,
					(state, ov) -> (int) CONNECT_NAMED_PIPE.invokeExact(state, handle.value, ov));
			if (result < 0 && result != -ERROR_PIPE_CONNECTED) {
				throw new IOException("ConnectNamedPipe failed: " + -result);
			}
			synchronized (this) {
				if (!open) {
					throw new IOException("Pipe closed");
				}
				pending = null;
				pending = createInstance();
				McpServer.Connection connection = new PipeConnection(handle);
				transferred = true;
				return connection;
			}
		} finally {
			if (!transferred) {
				synchronized (this) {
					if (pending == handle) {
						pending = null;
					}
				}
				handle.close();
			}
		}
	}

	/** Stops accepting, releasing a blocked {@link #accept()}. */
	synchronized void close() {
		if (open) {
			open = false;
			if (pending != null) {
				pending.close();
				pending = null;
			}
		}
	}

	private PipeHandle createInstance() throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment state = arena.allocate(CALL_STATE);
			MemorySegment handle = (MemorySegment) CREATE_NAMED_PIPE.invokeExact(state, wide(arena, name),
					PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED, PIPE_TYPE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
					PIPE_UNLIMITED_INSTANCES, BUFFER_SIZE, BUFFER_SIZE, 0, MemorySegment.NULL);
			if (handle.equals(INVALID_HANDLE_VALUE)) {
				throw new IOException("CreateNamedPipeW failed for " + name + ": " + lastError(state));
			}
			boolean transferred = false;
			try {
				PipeHandle owner = new PipeHandle(handle);
				transferred = true;
				return owner;
			} finally {
				if (!transferred) {
					closeHandle(handle);
				}
			}
		} catch (IOException e) {
			throw e;
		} catch (Throwable t) {
			throw new IOException(t);
		}
	}

	@FunctionalInterface
	private interface Start {
		int call(MemorySegment state, MemorySegment overlapped) throws Throwable;
	}

	/**
	 * Starts an overlapped operation and waits for it to finish.
	 *
	 * @return the number of bytes transferred, or the negated Win32 error code
	 */
	private static int overlapped(PipeHandle handle, Start start) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment state = arena.allocate(CALL_STATE);
			MemorySegment event = (MemorySegment) CREATE_EVENT.invokeExact(state, MemorySegment.NULL, 1, 0,
					MemorySegment.NULL);
			if (event.equals(MemorySegment.NULL)) {
				throw new IOException("CreateEventW failed: " + lastError(state));
			}
			try {
				MemorySegment ov = arena.allocate(OVERLAPPED_SIZE, 8);
				ov.set(ADDRESS, OVERLAPPED_EVENT, event);
				MemorySegment count = arena.allocate(JAVA_INT);
				synchronized (handle) {
					if (!handle.open) {
						throw new IOException("Pipe closed");
					}
					handle.operations++;
				}
				try {
					synchronized (handle) {
						if (!handle.open) {
							throw new IOException("Pipe closed");
						}
						// Issue under the close lock so cancellation cannot miss this operation.
						if (start.call(state, ov) == 0 && lastError(state) != ERROR_IO_PENDING) {
							return -lastError(state);
						}
					}
					if ((int) GET_OVERLAPPED_RESULT.invokeExact(state, handle.value, ov, count, 1) == 0) {
						return -lastError(state);
					}
					return count.get(JAVA_INT, 0);
				} finally {
					handle.finished();
				}
			} finally {
				closeHandle(event);
			}
		} catch (IOException e) {
			throw e;
		} catch (Throwable t) {
			throw new IOException(t);
		}
	}

	private static MemorySegment wide(Arena arena, String s) {
		return arena.allocateFrom(JAVA_BYTE, (s + '\0').getBytes(StandardCharsets.UTF_16LE));
	}

	private static int lastError(MemorySegment state) {
		return (int) LAST_ERROR.get(state, 0L);
	}

	private static final class PipeHandle {
		private final MemorySegment value;
		private boolean open = true;
		private int operations;

		PipeHandle(MemorySegment value) {
			this.value = value;
		}

		synchronized boolean isOpen() {
			return open;
		}

		synchronized void close() {
			if (!open) {
				return;
			}
			open = false;
			if (operations == 0) {
				closeHandle(value);
			} else {
				try {
					int ignored = (int) CANCEL_IO_EX.invokeExact(value, MemorySegment.NULL);
				} catch (Throwable t) {
					throw new IllegalStateException("CancelIoEx invocation failed", t);
				}
			}
		}

		synchronized void finished() {
			if (--operations == 0 && !open) {
				// Cancellation is only a request: retain the handle until every waiter completes.
				closeHandle(value);
			}
		}
	}

	private static void closeHandle(MemorySegment handle) {
		try {
			int ignored = (int) CLOSE_HANDLE.invokeExact(handle);
		} catch (Throwable t) {
			// ignore on close
		}
	}

	private static final class PipeConnection implements McpServer.Connection {

		private final PipeHandle handle;
		private final InputStream in = new PipeInputStream();
		private final OutputStream out = new PipeOutputStream();

		PipeConnection(PipeHandle handle) {
			this.handle = handle;
		}

		@Override
		public InputStream in() {
			return in;
		}

		@Override
		public OutputStream out() {
			return out;
		}

		@Override
		public boolean isOpen() {
			return handle.isOpen();
		}

		@Override
		public void close() {
			handle.close();
		}

		private final class PipeInputStream extends InputStream {
			@Override
			public int read() throws IOException {
				byte[] one = new byte[1];
				return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0) {
					return 0;
				}
				if (!isOpen()) {
					return -1;
				}
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment buffer = arena.allocate(Math.min(len, BUFFER_SIZE));
					int size = (int) buffer.byteSize();
					int n = overlapped(handle, (state, ov) -> (int) READ_FILE.invokeExact(state, handle.value, buffer, size,
							MemorySegment.NULL, ov));
					if (n < 0) {
						int error = -n;
						if (error == ERROR_BROKEN_PIPE || error == ERROR_PIPE_NOT_CONNECTED
								|| error == ERROR_OPERATION_ABORTED || !isOpen()) {
							return -1;
						}
						throw new IOException("ReadFile failed: " + error);
					}
					if (n == 0) {
						return -1;
					}
					MemorySegment.copy(buffer, JAVA_BYTE, 0, b, off, n);
					return n;
				}
			}
		}

		private final class PipeOutputStream extends OutputStream {
			@Override
			public void write(int b) throws IOException {
				write(new byte[] { (byte) b }, 0, 1);
			}

			@Override
			public synchronized void write(byte[] b, int off, int len) throws IOException {
				if (!isOpen()) {
					throw new IOException("Pipe closed");
				}
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment buffer = arena.allocate(Math.max(len, 1));
					MemorySegment.copy(b, off, buffer, JAVA_BYTE, 0, len);
					long written = 0;
					while (written < len) {
						MemorySegment slice = buffer.asSlice(written);
						int size = (int) (len - written);
						int n = overlapped(handle, (state, ov) -> (int) WRITE_FILE.invokeExact(state, handle.value, slice,
								size, MemorySegment.NULL, ov));
						if (n <= 0) {
							throw new IOException("WriteFile failed: " + -n);
						}
						written += n;
					}
				} catch (IOException e) {
					PipeConnection.this.close();
					throw e;
				}
			}
		}
	}
}
