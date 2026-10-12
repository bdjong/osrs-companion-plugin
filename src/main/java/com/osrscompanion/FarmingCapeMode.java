package com.osrscompanion;

/** The "Farming cape for herbs" setting: with 99 Farming, what a farm run with herbs does about the cape. */
public enum FarmingCapeMode {
	OFF("Off"),
	REMIND("Remind"),
	FORCE("Force");

	private final String name;

	FarmingCapeMode(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return name;
	}
}
