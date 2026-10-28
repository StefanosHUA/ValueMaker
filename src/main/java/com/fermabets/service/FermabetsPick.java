package com.fermabets.service;

import com.fermabets.model.FermabetsModel;
import com.fermabets.model.ProjectionBuilder.Projection;

/** An NBA pick priced by the Fermabets model from this season's projected bands. */
public record FermabetsPick(String team, String opp, boolean home, double mean, Projection projection,
                            FermabetsModel.Result result, FermabetsModel.Stake plan) implements PickInfo {
    @Override
    public double usage() {
        return projection.usage();
    }

    @Override
    public double formUsage() {
        return projection.usage();
    }

    @Override
    public double roleMinutes() {
        return projection.minutes();
    }

    public boolean fermabets() {
        return true;
    }
}
