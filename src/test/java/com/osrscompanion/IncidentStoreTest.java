package com.osrscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IncidentStoreTest {
	static final class MemoryStorage implements IncidentStore.Storage {
		String value;
		int flushes;

		@Override
		public String get() {
			return value;
		}

		@Override
		public void set(String v) {
			value = v;
		}

		@Override
		public void unset() {
			value = null;
		}

		@Override
		public void flush() {
			flushes++;
		}
	}

	@Test
	public void savedIncidentIsTakenOnceOnTheNextStart() {
		MemoryStorage storage = new MemoryStorage();
		new IncidentStore(storage).save(new IncidentStore.Incident("freeze", "farm-run", "froze\nfor 3 s",
			"Client thread\n\tat a.b(C.java:1)", 1234L));
		assertEquals(1, storage.flushes);

		IncidentStore next = new IncidentStore(storage);
		IncidentStore.Incident i = next.take();
		assertEquals("freeze", i.level);
		assertEquals("farm-run", i.feature);
		assertEquals("froze for 3 s", i.message);
		assertEquals("Client thread\n\tat a.b(C.java:1)", i.stack);
		assertEquals(1234L, i.timeMs);
		assertNull(next.take());
	}

	@Test
	public void clearRemovesIt() {
		MemoryStorage storage = new MemoryStorage();
		IncidentStore store = new IncidentStore(storage);
		store.save(new IncidentStore.Incident("fatal", "x", "OOM", null, 1L));
		store.clear();
		assertNull(store.take());
	}

	@Test
	public void garbageIsIgnored() {
		assertNull(IncidentStore.decode("nonsense"));
		assertNull(IncidentStore.decode("v1\nnot-a-number\na\nb\nc\nd"));
	}

	@Test
	public void stackIsShort() {
		Throwable t = new RuntimeException("x");
		String s = IncidentStore.shortStack(t, 2);
		assertTrue(s.startsWith("java.lang.RuntimeException: x"));
		assertEquals(3 + (t.getStackTrace().length > 2 ? 1 : 0), s.split("\n").length);
		String long1 = IncidentStore.encode(new IncidentStore.Incident("fatal", "f", "m", repeat('a', 10_000), 1L));
		assertTrue(long1.length() < IncidentStore.MAX_STACK + 100);
	}

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(c);
		}
		return sb.toString();
	}
}
