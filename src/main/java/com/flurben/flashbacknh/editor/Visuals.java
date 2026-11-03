package com.flurben.flashbacknh.editor;

import java.util.HashSet;
import java.util.Set;

/** Render settings of the replay viewer. Saved with the editor state. */
public final class Visuals {

    public enum Weather {
        RECORDED,
        CLEAR,
        RAIN,
        THUNDER
    }

    // GUI
    public boolean showChat = false;
    public boolean showScoreboard = false;
    public boolean showPlayerList = true;
    public boolean showBossBar = true;
    public boolean showRecordedHotbar = true;

    // World
    public boolean renderEntities = true;
    public boolean renderPlayers = true;
    public boolean renderParticles = true;
    public boolean renderSky = true;
    public boolean renderClouds = true;
    public boolean renderNametags = true;

    // Overrides
    public boolean overrideFov = false;
    public float fov = 70.0f;
    public boolean overrideTime = false;
    public int time = 6000;
    public Weather weather = Weather.RECORDED;
    public boolean nightVision = false;
    public float cameraRoll = 0.0f;
    public boolean cameraShake = false;
    public float shakeFrequency = 1.0f;
    public float shakeAmplitude = 1.0f;

    // Editor guides (never exported)
    public boolean ruleOfThirds = false;
    public boolean centerGuide = false;
    public boolean showCameraPath = true;

    /** Entities hidden by uuid (players) or entity id prefixed with '#'. */
    public Set<String> hiddenEntities = new HashSet<>();

    public Visuals copy() {
        Visuals v = new Visuals();
        v.showChat = showChat;
        v.showScoreboard = showScoreboard;
        v.showPlayerList = showPlayerList;
        v.showBossBar = showBossBar;
        v.showRecordedHotbar = showRecordedHotbar;
        v.renderEntities = renderEntities;
        v.renderPlayers = renderPlayers;
        v.renderParticles = renderParticles;
        v.renderSky = renderSky;
        v.renderClouds = renderClouds;
        v.renderNametags = renderNametags;
        v.overrideFov = overrideFov;
        v.fov = fov;
        v.overrideTime = overrideTime;
        v.time = time;
        v.weather = weather;
        v.nightVision = nightVision;
        v.cameraRoll = cameraRoll;
        v.cameraShake = cameraShake;
        v.shakeFrequency = shakeFrequency;
        v.shakeAmplitude = shakeAmplitude;
        v.ruleOfThirds = ruleOfThirds;
        v.centerGuide = centerGuide;
        v.showCameraPath = showCameraPath;
        v.hiddenEntities = new HashSet<>(hiddenEntities);
        return v;
    }
}
