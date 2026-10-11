package com.osrscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ItemWhereTest {
	@Test
	public void teammateSourcesSayWhereTheItemIs() {
		assertEquals("worn by Zeekr", ItemWhere.groupLabel("teammate:Zeekr:worn"));
		assertEquals("in Zeekr's inventory", ItemWhere.groupLabel("teammate:Zeekr:inventory"));
		assertEquals("in Zeekr's bank", ItemWhere.groupLabel("teammate:Zeekr:bank"));
		assertEquals("in Zeekr's bank", ItemWhere.groupLabel("teammate:Zeekr:bank_deposits"));
		assertEquals("carried by Zeekr", ItemWhere.groupLabel("teammate:Zeekr:carried"));
		assertEquals("in Iron Zee's seed vault", ItemWhere.groupLabel("teammate:Iron Zee:seed_vault"));
		assertEquals("older website: the teammate's bank", "in Zeekr's bank", ItemWhere.groupLabel("teammate:Zeekr"));
		assertEquals("in group storage", ItemWhere.groupLabel("group_storage"));
	}

	@Test
	public void ownSourcesAreNotGroupSources() {
		assertNull(ItemWhere.groupLabel("bank"));
		assertNull(ItemWhere.groupLabel(null));
		assertFalse(ItemWhere.isGroup("seed_vault"));
		assertTrue(ItemWhere.isGroup("teammate:Zeekr:worn"));
	}
}
