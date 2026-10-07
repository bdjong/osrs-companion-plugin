package com.osrscompanion.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * GET /api/runelite/slayer/guide/: the current task and where to do it, best place first.
 * {@code locations} is empty when the website has no data for the task.
 */
public class SlayerGuide {
	@SerializedName("task")
	private Task task;

	@SerializedName("locations")
	private List<Location> locations;

	/** The player's own pick on the website, else the recommended location's key. */
	@SerializedName("selected")
	private String selected;

	public Task getTask() {
		return task;
	}

	public List<Location> getLocations() {
		return locations == null ? Collections.emptyList() : locations;
	}

	public String getSelected() {
		return selected;
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
		@SerializedName("task_id")
		private Integer taskId;

		@SerializedName("task_name")
		private String taskName;

		@SerializedName("area_id")
		private Integer areaId;

		@SerializedName("area_name")
		private String areaName;

		@SerializedName("amount_remaining")
		private int amountRemaining;

		@SerializedName("amount_original")
		private int amountOriginal;

		@SerializedName("master")
		private String master;

		public Integer getTaskId() {
			return taskId;
		}

		public String getTaskName() {
			return taskName;
		}

		public Integer getAreaId() {
			return areaId;
		}

		public String getAreaName() {
			return areaName;
		}

		public int getAmountRemaining() {
			return amountRemaining;
		}

		public int getAmountOriginal() {
			return amountOriginal;
		}

		public String getMaster() {
			return master;
		}
	}

	public static class Location {
		@SerializedName("key")
		private String key;

		@SerializedName("name")
		private String name;

		/** Where to stand / the centre of the area: x, y, plane. */
		@SerializedName("tile")
		private List<Integer> tile;

		/** Tiles from {@link #tile} that still count as being in the area. */
		@SerializedName("radius")
		private int radius;

		/** Map regions that count as the area (instances, dungeons); may be empty. */
		@SerializedName("region_ids")
		private List<Integer> regionIds;

		@SerializedName("multi")
		private boolean multi;

		@SerializedName("recommended")
		private boolean recommended;

		@SerializedName("why")
		private String why;

		@SerializedName("requirements")
		private List<String> requirements;

		/** The way there, same shape as a farm route stop's. */
		@SerializedName("travel")
		private FarmRoute.Travel travel;

		@SerializedName("steps")
		private List<String> steps;

		/** NPC names that count as the task monster here. */
		@SerializedName("monsters")
		private List<String> monsters;

		public String getKey() {
			return key;
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

		public int getRadius() {
			return radius;
		}

		public List<Integer> getRegionIds() {
			return regionIds == null ? Collections.emptyList() : regionIds;
		}

		public boolean isMulti() {
			return multi;
		}

		public boolean isRecommended() {
			return recommended;
		}

		public String getWhy() {
			return why == null ? "" : why;
		}

		public List<String> getRequirements() {
			return requirements == null ? Collections.emptyList() : requirements;
		}

		/** Null when the website sent no way there. */
		public FarmRoute.Travel getTravel() {
			return travel;
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
	}
}
