package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ItemWhereTest {
	@Test
	public void teammateSourcesSayWhereTheItemIs() {
		assertEquals("worn by Bob", ItemWhere.groupLabel("teammate:Bob:worn"));
		assertEquals("in Bob's inventory", ItemWhere.groupLabel("teammate:Bob:inventory"));
		assertEquals("in Bob's bank", ItemWhere.groupLabel("teammate:Bob:bank"));
		assertEquals("in Bob's bank", ItemWhere.groupLabel("teammate:Bob:bank_deposits"));
		assertEquals("carried by Bob", ItemWhere.groupLabel("teammate:Bob:carried"));
		assertEquals("in Iron Zee's seed vault", ItemWhere.groupLabel("teammate:Iron Zee:seed_vault"));
		assertEquals("older website: the teammate's bank", "in Bob's bank", ItemWhere.groupLabel("teammate:Bob"));
		assertEquals("in group storage", ItemWhere.groupLabel("group_storage"));
	}

	@Test
	public void ownSourcesAreNotGroupSources() {
		assertNull(ItemWhere.groupLabel("bank"));
		assertNull(ItemWhere.groupLabel(null));
		assertFalse(ItemWhere.isGroup("seed_vault"));
		assertTrue(ItemWhere.isGroup("teammate:Bob:worn"));
	}
}
