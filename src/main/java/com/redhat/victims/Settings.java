/*
 * Copyright (C) 2012 Red Hat Inc.
 *
 * This file is part of enforce-victims-rule for the Maven Enforcer Plugin.
 * enforce-victims-rule is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * enforce-victims-rule is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with enforce-victims-rule.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.redhat.victims;

import java.util.*;
import org.apache.commons.httpclient.HttpURL;
import org.apache.commons.httpclient.HttpsURL;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.maven.plugin.logging.Log;
import org.json.JSONObject;

/**
 * Configuration settings are defined and stored by this class. All settings of
 * the VictimsRule are validated and initialized with defaults from this class.
 *
 * @author gmurphy
 */
public final class Settings {

    /*
     * Different modes of operation for the plugin
     */
    public static final String MODE_WARNING     = "warning";
    public static final String MODE_FATAL       = "fatal";
    public static final String MODE_DISABLED    = "disabled";
    /*
     * Allow developers to disable automatic updates
     */
    public static final String UPDATES_DISABLED = "offline";
    public static final String UPDATES_AUTO     = "auto";
    /*
     * Different settings that can be configured. These need to map back to the
     * names of each of the private members in the rule definition in order to
     * be configurable in the pom.xml @see VictimsRule
     */
    public static final String URL              = "url";
    public static final String METADATA         = "metadata";
    public static final String FINGERPRINT      = "fingerprint";
    public static final String UPDATE_DATABASE  = "updates";
    public static final String DATABASE_DRIVER  = "dbdriver";
    public static final String DATABASE_URL     = "dburl";
    public static final String TOLERANCE        = "tolerance";
    public static final String SEARCH_EXTENDED  = "metadataplus";


    /**
     * Reasonably sensible defaults
     */
    public static final Map<String, String> defaults;

    static {
        Map<String, String> mappings = new HashMap<String, String>();
        mappings.put(URL, "https://victims-websec.rhcloud.com/service/v1");
        mappings.put(METADATA, MODE_WARNING);
        mappings.put(FINGERPRINT, MODE_FATAL);
        mappings.put(DATABASE_URL, "jdbc:derby:.victims;create=true");
        mappings.put(DATABASE_DRIVER, "org.apache.derby.jdbc.EmbeddedDriver");
        mappings.put(TOLERANCE, "0.75");
        mappings.put(UPDATE_DATABASE, UPDATES_AUTO);
        mappings.put(SEARCH_EXTENDED, "false");
        defaults = Collections.unmodifiableMap(mappings);
    }
    /**
     * Map containing configuration values
     */
    private Map<String, String> settings;


    /**
     * Generic interface used validate settings in the configuration
     */
    private interface Validator {

        public void validate() throws VictimsException;
    }
    /**
     * FIXME This is nasty
     */
    private Validator[] required = {
        (new Validator() {

            public void validate() throws VictimsException {

                try {

                    final String entry = settings.get(URL);
                    if (entry == null || entry.length() <= 0) {
                        throw new VictimsException(IOUtils.fmt(Resources.ERR_SETTING_MISSING, URL));
                    }

                    URI url;
                    try {
                        url = new HttpURL(entry);

                    } catch (URIException e) {
                        url = new HttpsURL(entry);
                    }

                    if (!url.getPath().endsWith("service/v1")) {
                        throw new VictimsException(IOUtils.fmt(Resources.ERR_INVALID_URL, url.toString()));
                    }

                } catch (URIException e) {
                    throw new VictimsException(IOUtils.fmt(Resources.ERR_INVALID_URL, settings.get(URL)));
                }
            }
        }),
        (new Validator() {

            public void validate() throws VictimsException {

                List<String> modes = new ArrayList<String>();
                modes.add(MODE_FATAL);
                modes.add(MODE_WARNING);
                modes.add(MODE_DISABLED);

                List<String> modeSettings = new ArrayList<String>();
                modeSettings.add(METADATA);
                modeSettings.add(FINGERPRINT);

                for (String item : modeSettings) {
                    String value = settings.get(item);
                    if (value == null) {
                        throw new VictimsException(IOUtils.fmt(Resources.ERR_SETTING_MISSING, item));
                    }

                    if (!modes.contains(value)) {
                        String err = IOUtils.fmt(Resources.ERR_INVALID_MODE, value, item, modes.toString());
                        throw new VictimsException(err);
                    }
                }
            }
        })
    };

    /**
     * Creates a new empty settings instance
     */
    public Settings() {
        settings = new HashMap<String, String>();
    }

    /**
     * Add new setting for the specified key.
     * @param k The key to add to the settings.
     * @param v The value to associate with the supplied key.
     */
    public void set(String k, String v) {
        settings.put(k, v);
    }

    /**
     * Retrieve a setting via specified key.
     * @parma k The key to lookup in they configuration settings.
     * @return Value for setting.
     */
    public String get(String k) {
        return settings.get(k);
    }

    /**
     * Use the supplied log to display the current settings.
     * @param log Log to send output to.
     */
    public void show(Log log) {
        JSONObject obj = new JSONObject(settings);
        log.info(IOUtils.prettyPrint(IOUtils.fmt(Resources.INFO_SETTINGS_HEADING), obj));
    }

    /**
     * Validate the current settings against a list of
     * internal validation rules.
     * @throws VictimsException When validation fails.
     */
    public void validate() throws VictimsException {
        for (Validator v : required) {
            v.validate();
        }
    }

    /**
     * Returns true if the setting is in fatal mode. Used when
     * determining if the rule should fail a build.
     * @param setting The configuration item to check if in fatal mode.
     * @return True when the setting is in fatal mode.
     */
    public boolean inFatalMode(String setting) {
        String val = settings.get(setting);
        return val != null && val.equalsIgnoreCase(MODE_FATAL);
    }

    /**
     * Returns true if the value associated with the supplied
     * key isn't set to disabled.
     * @param setting The setting to check if is disabled.
     * @return  True if the setting is enabled.
     */
    public boolean isEnabled(String setting) {
        String val = settings.get(setting);
        return val != null && !val.equalsIgnoreCase(MODE_DISABLED);
    }

    /**
     * Returns true if automatic updates are enabled.
     * @return True if automatic updates of database are enabled.
     */
    public boolean updatesEnabled() {
        String val = settings.get(UPDATE_DATABASE);
        return val != null && val.equalsIgnoreCase(UPDATES_AUTO);
    }


    public boolean extendedMetadataEnabled() {
        String val = settings.get(SEARCH_EXTENDED);
        return val != null && val.equalsIgnoreCase("true");
    }
}
