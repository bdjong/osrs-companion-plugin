package com.osrscompanion.model;

import lombok.Getter;
import com.google.gson.annotations.SerializedName;
import java.util.*;

/**
 * GET /api/runelite/slayer/guide/: the current task and where to do it, best place first.
 * {@code locations} is empty when the website has no data for the task.
 */
public class SlayerGuide {
	@Getter
	@SerializedName("task")
	private Task task;

	@SerializedName("locations")
	private List<Location> locations;

	/** The player's own pick on the website, else the recommended location's key. */
	@Getter
	@SerializedName("selected")
	private String selected;

	public List<Location> getLocations() {
		return locations == null ? Collections.emptyList() : locations;
	}

	/** The location to guide to: the selected one, else the recommended one, else the first; null when there is none. */
	public Location selectedLocation() {
		List<Location> all = getLocations();
		for (Location l : all) {
			if (l.getKey() != null && l.getKey().equals(selected)) {
				return l;
			}
		}
		for (Location l : all) {
			if (l.isRecommended()) {
				return l;
			}
		}
		return all.isEmpty() ? null : all.get(0);
	}

	public static class Task {
		@Getter
		@SerializedName("task_id")
		private Integer taskId;

		@Getter
		@SerializedName("task_name")
		private String taskName;

		@Getter
		@SerializedName("area_id")
		private Integer areaId;

		@Getter
		@SerializedName("area_name")
		private String areaName;

		@Getter
		@SerializedName("amount_remaining")
		private int amountRemaining;

		@Getter
		@SerializedName("amount_original")
		private int amountOriginal;

		@Getter
		@SerializedName("master")
		private String master;

	}

	public static class Location {
		@Getter
		@SerializedName("key")
		private String key;

		@SerializedName("name")
		private String name;

		/** Where to stand / the centre of the area: x, y, plane. */
		@SerializedName("tile")
		private List<Integer> tile;

		/** Tiles from {@link #tile} that still count as being in the area. */
		@Getter
		@SerializedName("radius")
		private int radius;

		/** Map regions that count as the area (instances, dungeons); may be empty. */
		@SerializedName("region_ids")
		private List<Integer> regionIds;

		@Getter
		@SerializedName("multi")
		private boolean multi;

		@Getter
		@SerializedName("recommended")
		private boolean recommended;

		@SerializedName("why")
		private String why;

		@SerializedName("requirements")
		private List<String> requirements;

		/** The way there, same shape as a farm route stop's. */
		@Getter
		@SerializedName("travel")
		private FarmRoute.Travel travel;

		@SerializedName("steps")
		private List<String> steps;

		/**
		 * Tiles on the way from the entrance, in order (a passage, a cave entrance), each x, y, plane: the hint arrow
		 * points at the furthest one in the loaded scene, then at the location's tile.
		 */
		@SerializedName("waypoints")
		private List<List<Integer>> waypoints;

		/** NPC names that count as the task monster here. */
		@SerializedName("monsters")
		private List<String> monsters;

		/** An area the plugin makes itself (round a task's boss, see SlayerBosses): a tile and a radius. */
		public static Location area(String key, String name, int x, int y, int plane, int radius, List<String> monsters) {
			Location l = new Location();
			l.key = key;
			l.name = name;
			l.tile = Arrays.asList(x, y, plane);
			l.radius = radius;
			l.monsters = monsters == null ? null : new ArrayList<>(monsters);
			return l;
		}

		public String getName() {
			return name == null ? "Unknown location" : name;
		}

		/** True when the tile has x, y and plane. */
		public boolean hasTile() {
			return tile != null && tile.size() >= 3 && tile.get(0) != null && tile.get(1) != null && tile.get(2) != null;
		}

		public int getX() {
			return hasTile() ? tile.get(0) : 0;
		}

		public int getY() {
			return hasTile() ? tile.get(1) : 0;
		}

		public int getPlane() {
			return hasTile() ? tile.get(2) : 0;
		}

		public List<Integer> getRegionIds() {
			return regionIds == null ? Collections.emptyList() : regionIds;
		}

		public String getWhy() {
			return why == null ? "" : why;
		}

		public List<String> getRequirements() {
			return requirements == null ? Collections.emptyList() : requirements;
		}

		/** The way points as x, y, plane triples (malformed ones left out), in order. */
		public List<int[]> getWaypoints() {
			List<int[]> out = new ArrayList<>();
			if (waypoints != null) {
				for (List<Integer> w : waypoints) {
					if (w != null && w.size() >= 3 && w.get(0) != null && w.get(1) != null && w.get(2) != null) {
						out.add(new int[]{w.get(0), w.get(1), w.get(2)});
					}
				}
			}
			return out;
		}

		public List<String> getSteps() {
			return steps == null ? Collections.emptyList() : steps;
		}

		public List<String> getMonsters() {
			return monsters == null ? Collections.emptyList() : monsters;
		}

		/**
		 * Whether a player at (x, y, plane) in this map region is in the area: within the radius of the
		 * tile on the same plane, or anywhere in one of its regions.
		 */
		public boolean contains(int x, int y, int plane, int regionId) {
			if (getRegionIds().contains(regionId)) {
				return true;
			}
			if (!hasTile() || plane != getPlane()) {
				return false;
			}
			return Math.max(Math.abs(x - getX()), Math.abs(y - getY())) <= Math.max(0, radius);
		}

		/**
		 * Every map region the location spans: its region ids plus each region the square of the radius
		 * round its tile reaches (the website's locationRegions, so a kill's region and the area agree).
		 * A location over several regions (a dungeon's caves) is one area in all of them.
		 */
		public Set<Integer> coveredRegions() {
			Set<Integer> out = new LinkedHashSet<>(getRegionIds());
			if (hasTile()) {
				int r = Math.max(0, radius);
				for (int rx = (getX() - r) >> 6; rx <= (getX() + r) >> 6; rx++) {
					for (int ry = (getY() - r) >> 6; ry <= (getY() + r) >> 6; ry++) {
						out.add(rx << 8 | ry);
					}
				}
			}
			return out;
		}

		/** Whether you are in the area ({@link #contains}) or anywhere in one of the regions it spans ({@link #coveredRegions}). */
		public boolean covers(int x, int y, int plane, int regionId) {
			return contains(x, y, plane, regionId) || coveredRegions().contains(regionId);
		}
	}
}
