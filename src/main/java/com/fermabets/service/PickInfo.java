package com.fermabets.service;

/** What the pick cards show about the player's game and role, whichever model priced the bet. */
public interface PickInfo {
    String team();

    String opp();

    boolean home();

    double mean();

    double usage();

    double formUsage();

    double roleMinutes();
}
