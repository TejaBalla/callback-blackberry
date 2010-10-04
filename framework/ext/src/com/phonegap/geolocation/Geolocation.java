package com.phonegap.geolocation;

import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.microedition.location.Criteria;
import javax.microedition.location.Location;
import javax.microedition.location.LocationException;
import javax.microedition.location.LocationProvider;

import net.rim.device.api.gps.BlackBerryCriteria;
import net.rim.device.api.gps.BlackBerryLocationProvider;
import net.rim.device.api.gps.GPSInfo;
import net.rim.device.api.script.ScriptEngine;

import org.json.me.JSONArray;
import org.json.me.JSONException;
import org.json.me.JSONObject;

import com.phonegap.PhoneGapExtension;
import com.phonegap.api.Plugin;
import com.phonegap.api.PluginResult;
import com.phonegap.util.Logger;

public class Geolocation implements Plugin {

	/**
	 * Possible actions.
	 */
	protected static final int ACTION_WATCH = 0;
	protected static final int ACTION_CLEAR_WATCH = 1;
	protected static final int ACTION_GET_POSITION = 2;
	protected static final int ACTION_SHUTDOWN = 3;
	
	/**
	 * Callback ID argument index.
	 */
	protected static final int ARG_CALLBACK_ID = 0;

	/**
	 * Minimum GPS accuracy (meters).
	 */
	protected static final float MIN_GPS_ACCURACY = 10F; // meters

	/**
	 * Hash of all the listeners created, keyed on callback ids.
	 */
	protected final Hashtable geoListeners;
	
	/**
	 * Constructor.
	 */
	public Geolocation() {
		this.geoListeners = new Hashtable();
	}

	/**
	 * Sets the script engine to allow plugins to interact with and 
	 * execute browser scripts. 
	 *  
	 * @param app The script engine of the widget application.
	 */
	public void setContext(ScriptEngine app) {
	}
	
	/**
	 * Identifies if action to be executed returns a value and should be run synchronously.
	 * 
	 * @param action	The action to execute
	 * @return			T=returns value
	 */
	public boolean isSynch(String action) {
		return false;
	}
	
	/**
	 * Executes the specified geolocation action.
	 *  
	 * @param action 
	 * 	  "getCurrentPosition" - Retrieves current location.
	 * 	  "watchPosition"      - Establishes a location provider that is keyed on specified position options
	 *                           and attaches a listener that notifies registered callbacks of location updates.
	 *    "stop"               - Clears the watch identified by the watch ID that must be specified in args.
	 *    "shutdown"           - Stops all listeners and resets all location providers.
	 * @param callbackId callback managed by the plugin manager (ignored)
	 * @param args contains the callback id and position options 
	 */
	public PluginResult execute(String action, String callbackId, JSONArray args) {
		
		// TODO: (6.0 only) Best practice is to determine if location service is on.
		// If not, prompt the user if OK to turn on.  
		// Should include an action 'isLocationOn'.
		//LocationInfo.isLocationOn();
		//LocationInfo.setLocationOn();
		//LocationInfo.isModeAvailable();
		
		/*
		 * The geolocation plugin bypasses the plugin callback framework for 
		 * success callbacks because the current implementation of the framework 
		 * deletes the callbacks after they have been called.  The geolocation 
		 * listener callbacks need to continue listening for location changes, 
		 * and are therefore managed separately from the plugin framework.  
		 * 
		 * This means the invoking script must pass the listener callback ID in 
		 * the args parameter (along with the position options).  The callbackId
		 * parameter (used by the plugin framework) is ignored.
		 * 
		 * The invoking script should still provide a failure callback so the 
		 * plugin framework can handle general error reporting.
		 */
		String listenerCallbackId;
		try {
			listenerCallbackId = args.getString(ARG_CALLBACK_ID);
		} catch (JSONException e) {
			return new PluginResult(PluginResult.Status.JSONEXCEPTION, "Callback ID argument is not valid.");
		}
		
		if (!GPSInfo.isGPSModeAvailable(GPSInfo.GPS_DEVICE_INTERNAL)){
			return new PluginResult(GeolocationStatus.GPS_NOT_AVAILABLE);			
		}
		
		PositionOptions options;
		switch (getAction(action)) {
			case ACTION_CLEAR_WATCH:
				clearWatch(listenerCallbackId);
				return null;

			case ACTION_WATCH:

				try {
					options = PositionOptions.fromJSONArray(args);
				} catch (NumberFormatException e) {
					return new PluginResult(PluginResult.Status.ILLEGAL_ARGUMENT_EXCEPTION, "One of the position options is not a valid number.");					
				} catch (JSONException e) {
					return new PluginResult(PluginResult.Status.JSONEXCEPTION, "One of the position options is not valid JSON.");
				}

				this.watchPosition(listenerCallbackId, options);
				return null;
				
			case ACTION_GET_POSITION:

				try {
					options = PositionOptions.fromJSONArray(args);
				} catch (NumberFormatException e) {
					return new PluginResult(PluginResult.Status.ILLEGAL_ARGUMENT_EXCEPTION, "One of the position options is not a valid number.");					
				} catch (JSONException e) {
					return new PluginResult(PluginResult.Status.JSONEXCEPTION, "One of the position options is not valid JSON.");
				}

				this.getCurrentPosition(listenerCallbackId, options);
				return null;

			case ACTION_SHUTDOWN:
				this.shutdown();
				return null;
		}
		
		return new PluginResult(PluginResult.Status.INVALIDACTION, "Geolocation: invalid action " + action);
	}

	/**
	 * Checks if the provided location is valid.
	 * @param location
	 * @return true if the location is valid
	 */
	protected boolean isLocationValid(Location location) {
		return location != null && location.isValid();
	}
	
	/**
	 * Checks if the provided location is fresh or not.
	 * @param po
	 * @param location
	 * @return true if the location is newer than maximum age in position options
	 */
	protected boolean isLocationFresh(PositionOptions po, Location location) {
		return new Date().getTime() - location.getTimestamp() < po.maxAge;
	}
	
	/**
	 * Checks if the accuracy of the location is high enough.
	 * @param po
	 * @param location
	 * @return
	 */
	protected boolean isLocationAccurate(PositionOptions po, Location location) {
		return po.enableHighAccuracy && location.getQualifiedCoordinates().getHorizontalAccuracy() < MIN_GPS_ACCURACY;
	}
	
	/**
	 * Retrieves a location provider with some criteria.
	 * @param po position options
	 * @return
	 */
	protected static LocationProvider getLocationProvider(PositionOptions po) {
		// configure criteria for location provider
		// Note: being too restrictive will make it less likely that one will be returned
		BlackBerryCriteria criteria = new BlackBerryCriteria();
		
		// can we get GPS info from the wifi network?
		if (GPSInfo.isGPSModeAvailable(GPSInfo.GPS_MODE_ASSIST))
			criteria.setMode(GPSInfo.GPS_MODE_ASSIST);
		// relies on device GPS receiver - not good indoors or if obstructed
		else if (GPSInfo.isGPSModeAvailable(GPSInfo.GPS_MODE_AUTONOMOUS))
			criteria.setMode(GPSInfo.GPS_MODE_AUTONOMOUS);		
		
		criteria.setAltitudeRequired(true);
		criteria.setPreferredResponseTime(po.timeout);
		
		// enable full power usage to increase location accuracy
		if (po.enableHighAccuracy) {
			criteria.setPreferredPowerConsumption(Criteria.POWER_USAGE_HIGH);
		}
		
		// Attempt to get a location provider
		BlackBerryLocationProvider provider;
		try {
			// Note: this could return an existing provider that meets above criteria
			provider  = (BlackBerryLocationProvider) LocationProvider.getInstance(criteria);
		} catch (LocationException e) {
			// all LocationProviders are currently permanently unavailable :(
			provider = null;
		}
		
		return provider;
	}
	
	/**
	 * Creates a location listener registers the specified callback with the listener.
	 * @param callbackId callback to receive location updates
	 * @param po position options
	 * @return
	 */
	protected void watchPosition(String callbackId, PositionOptions po) {
		GeolocationListener listener;

		// attempt to retrieve a location provider 
		LocationProvider lp = getLocationProvider(po);
		if (lp == null) {
			invokeErrorCallback(callbackId, 
					new GeolocationResult(GeolocationStatus.GPS_NOT_AVAILABLE));
			return;
		}

		// create a listener for retrieving location updates
		try {
			listener = new GeolocationListener(lp, callbackId, po);
		} catch (IllegalArgumentException e) {
			// if 	interval < -1, or 
			// if 	(interval != -1) and 
			//		(timeout > interval or maxAge > interval or 
			//			(timeout < 1 and timeout != -1) or 
			//			(maxAge < 1 and maxAge != -1)
			//		) 
			invokeErrorCallback(callbackId, 
					new GeolocationResult(GeolocationStatus.GPS_ILLEGAL_ARGUMENT_EXCEPTION, e.getMessage()));
			return;
		}

		// store the listener
		this.geoListeners.put(callbackId, listener);			
	}

	/**
	 * Shuts down all location listeners.
	 * @return
	 */
	protected void shutdown() {
		synchronized(this.geoListeners) {
			for (Enumeration listeners = this.geoListeners.elements(); listeners.hasMoreElements(); ) {
				GeolocationListener listener = (GeolocationListener) listeners.nextElement();
				listener.shutdown();
			}
			this.geoListeners.clear();
		}
	}

	/**
	 * Clears the watch for the specified callback id.
	 * If no more watches exist for the location provider, it is shut down.
	 * @param callbackId
	 * @return
	 */
	protected void clearWatch(String callbackId) {
		GeolocationListener listener = (GeolocationListener) this.geoListeners.get(callbackId);			
		listener.shutdown();
		this.geoListeners.remove(callbackId);
	}
	
	/**
	 * Returns a PluginResult with status OK and a JSON object representing the coords
	 * @param callbackId
	 * @param po
	 * @return
	 */
	protected void getCurrentPosition(String callbackId, PositionOptions options) {
		// Check the device for its last known location (may have come from 
		// another app on the device that has already requested a location)
		Location location = LocationProvider.getLastKnownLocation();
		if (location != null) 
			Logger.log( this.getClass().getName() + ": last known location=" +
				String.valueOf(location.getQualifiedCoordinates().getLatitude()) + ", " +
				String.valueOf(location.getQualifiedCoordinates().getLongitude()) );
		
		if (!isLocationValid(location) || !isLocationFresh(options, location) || !isLocationAccurate(options, location)) {

			LocationProvider lp = getLocationProvider(options);
			try {
				Logger.log(this.getClass().getName() + ": Retrieving location");
				location = lp.getLocation(options.timeout/1000);
			} catch(LocationException e) {
				Logger.log(this.getClass().getName() + ": " + e.getMessage());
				lp.reset();
				invokeErrorCallback(callbackId, 
						new GeolocationResult(GeolocationStatus.GPS_TIMEOUT));
				return;
			} catch (InterruptedException e) {
				Logger.log(this.getClass().getName() + ": " + e.getMessage());
				lp.reset();
				invokeErrorCallback(callbackId, 
						new GeolocationResult(GeolocationStatus.GPS_INTERUPTED_EXCEPTION));
				return;
			}
		}
		
		// now convert the location to a JSON object and return it in the PluginResult
		JSONObject position = null;
		try {
			position = Position.fromLocation(location).toJSONObject();
		} catch (JSONException e) {
			invokeErrorCallback(callbackId, 
				new GeolocationResult(PluginResult.Status.JSONEXCEPTION, "Converting the location to a JSON object failed"));
			return;
		}
		
		// invoke the geolocation callback
		Logger.log(this.getClass().getName() + ": current position=" + position);
		invokeSuccessCallback(callbackId, 
				new GeolocationResult(GeolocationResult.Status.OK, position));
	}
	
	/**
	 * Returns action to perform.
	 * @param action 
	 * @return action to perform
	 */
	protected static int getAction(String action) {
		if ("watchPosition".equals(action)) return ACTION_WATCH;
		if ("stop".equals(action)) return ACTION_CLEAR_WATCH;
		if ("getCurrentPosition".equals(action)) return ACTION_GET_POSITION;
		if ("shutdown".endsWith(action)) return ACTION_SHUTDOWN; 
		return -1;
	}	

	/**
	 * Invokes the specified geolocation success callback. 
	 * @param callbackId geolocation listener id
	 * @param geolocation result
	 */
	protected void invokeSuccessCallback(String callbackId, GeolocationResult result) {
		PhoneGapExtension.invokeSuccessCallback(callbackId, result);        
	}

	/**
	 * Invokes the specified geolocation error callback. 
	 * @param callbackId geolocation listener id
	 * @param geolocation result
	 */
	protected void invokeErrorCallback(String callbackId, GeolocationResult result) {
		PhoneGapExtension.invokeErrorCallback(callbackId, result);        
	}
}
