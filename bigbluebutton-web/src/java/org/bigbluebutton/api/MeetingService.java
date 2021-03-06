package org.bigbluebutton.api;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.*;
import org.bigbluebutton.api.domain.Meeting;
import org.bigbluebutton.api.domain.Playback;
import org.bigbluebutton.api.domain.Recording;
import org.bigbluebutton.api.domain.User;
import org.bigbluebutton.api.messaging.MessageListener;
import org.bigbluebutton.api.messaging.MessagingService;
import org.bigbluebutton.web.services.ExpiredMeetingCleanupTimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MeetingService {
	private static Logger log = LoggerFactory.getLogger(MeetingService.class);
	
	private final ConcurrentMap<String, Meeting> meetings;	
	private int defaultMeetingExpireDuration = 1;	
	private int defaultMeetingCreateJoinDuration = 5;
	private RecordingService recordingService;
	private MessagingService messagingService;
	private ExpiredMeetingCleanupTimerTask cleaner;
	private boolean removeMeetingWhenEnded = false;
	
	public MeetingService() {
		meetings = new ConcurrentHashMap<String, Meeting>();		
	}
	
	/**
	 * Remove the meetings that have ended from the list of
	 * running meetings.
	 */
	public void removeExpiredMeetings() {
		log.info("Cleaning up expired meetings");
		for (Meeting m : meetings.values()) {
			if (m.hasExpired(defaultMeetingExpireDuration)) {
				log.info("Removing expired meeting [id={} , name={}]", m.getInternalId(), m.getName());
				log.info("Expired meeting [start={} , end={}]", m.getStartTime(), m.getEndTime());
		  		if (m.isRecord()) {
		  			log.debug("[" + m.getInternalId() + "] is recorded. Process it.");		  			
		  			processRecording(m.getInternalId());
		  		}
				meetings.remove(m.getInternalId());
				continue;
			}
			
			if (m.wasNeverStarted(defaultMeetingCreateJoinDuration)) {
				log.info("Removing non-joined meeting [{} - {}]", m.getInternalId(), m.getName());
				meetings.remove(m.getInternalId());
				continue;
			}
			
			if (m.hasExceededDuration()) {
				log.info("Forcibly ending meeting [{} - {}]", m.getInternalId(), m.getName());
				m.setForciblyEnded(true);
				endMeeting(m.getInternalId());
			}
		}
	}
	
	public Collection<Meeting> getMeetings() {
		log.debug("The number of meetings are: " + meetings.size());
		return meetings.isEmpty() ? Collections.<Meeting>emptySet() : Collections.unmodifiableCollection(meetings.values());
	}
	
	public void createMeeting(Meeting m) {
		log.debug("Storing Meeting with internal id:" + m.getInternalId());
		meetings.put(m.getInternalId(), m);
		if (m.isRecord()) {
			Map<String,String> metadata=new HashMap<String,String>();
			metadata.putAll(m.getMetadata());
			//TODO: Need a better way to store these values for recordings
			metadata.put("meetingId", m.getExternalId());
			metadata.put("meetingName", m.getName());
			
			messagingService.recordMeetingInfo(m.getInternalId(), metadata);
		}
	}

	public Meeting getMeeting(String meetingId) {
		if(meetingId == null)
			return null;
		for (String key : meetings.keySet()) {
			if (key.startsWith(meetingId))
				return (Meeting) meetings.get(key);
		}
		
		return null;
	}

	public HashMap<String,Recording> getRecordings(ArrayList<String> idList) {
		//TODO: this method shouldn't be used 
		HashMap<String,Recording> recs= reorderRecordings(recordingService.getRecordings(idList));
		return recs;
	}
	
	public HashMap<String,Recording> reorderRecordings(ArrayList<Recording> olds){
		HashMap<String,Recording> map= new HashMap<String, Recording>();
		for(Recording r:olds){
			if(!map.containsKey(r.getId())){
				Map<String,String> meta= r.getMetadata();
				String mid = meta.remove("meetingId");
				String name = meta.remove("meetingName");
				
				r.setMeetingID(mid);
				r.setName(name);

				ArrayList<Playback> plays=new ArrayList<Playback>();
				
				plays.add(new Playback(r.getPlaybackFormat(), r.getPlaybackLink(), getDurationRecording(r.getEndTime(), r.getStartTime())));
				r.setPlaybacks(plays);
				map.put(r.getId(), r);
			}
			else{
				Recording rec=map.get(r.getId());
				rec.getPlaybacks().add(new Playback(r.getPlaybackFormat(), r.getPlaybackLink(), getDurationRecording(r.getEndTime(), r.getStartTime())));
			}
		}
		
		return map;
	}
	
	private int getDurationRecording(String end, String start){
		// Date Format for recordings: Thu Mar 04 14:05:56 UTC 2010
		SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d HH:mm:ss z yyyy");
		int duration;
		try{
			Calendar cal=Calendar.getInstance();
			
			cal.setTime(sdf.parse(end));
			long end_time=cal.getTimeInMillis();
			
			cal.setTime(sdf.parse(start));
			long start_time=cal.getTimeInMillis();
			
			duration = (int)Math.ceil((end_time - start_time)/60000.0);
		}catch(Exception e){
			log.debug(e.getMessage());
			duration = 0;
		}
		return duration;
	}
	
	public boolean existsAnyRecording(ArrayList<String> idList){
		return recordingService.existAnyRecording(idList);
	}
	
	public void setPublishRecording(ArrayList<String> idList,boolean publish){
		for(String id:idList){
			recordingService.publish(id,publish);
		}
	}
	
	public void setRemoveMeetingWhenEnded(boolean s) {
		removeMeetingWhenEnded = s;
	}
	
	public void deleteRecordings(ArrayList<String> idList){
		for(String id:idList){
			recordingService.delete(id);
		}
	}
	
	public void processRecording(String meetingId) {
		log.debug("Process recording for [{}]", meetingId);
		Meeting m = getMeeting(meetingId);
		if (m != null) {
			int numUsers = m.getNumUsers();
			if (numUsers == 0) {
				recordingService.startIngestAndProcessing(meetingId);		
			} else {
				log.debug("Meeting [{}] is not empty with {} users.", meetingId, numUsers);
			}
		} else {
			log.warn("Meeting [{}] does not exist.", meetingId);
		}
	}
		
	public boolean isMeetingWithVoiceBridgeExist(String voiceBridge) {
/*		Collection<Meeting> confs = meetings.values();
		for (Meeting c : confs) {
	        if (voiceBridge == c.getVoiceBridge()) {
	        	return true;
	        }
		}
*/		return false;
	}
	
	public void send(String channel, String message) {
		messagingService.send(channel, message);
	}
	
	public void endMeeting(String meetingId) {		
		messagingService.endMeeting(meetingId);
		
		if (removeMeetingWhenEnded) {
			meetings.remove(meetingId);
		} else {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				m.setForciblyEnded(true);
			}			
		}
	}

	public void setDefaultMeetingCreateJoinDuration(int expiration) {
		this.defaultMeetingCreateJoinDuration = expiration;
	}
	
	public void setDefaultMeetingExpireDuration(int meetingExpiration) {
		this.defaultMeetingExpireDuration = meetingExpiration;
	}

	public void setRecordingService(RecordingService s) {
		recordingService = s;
	}
	
	public void setMessagingService(MessagingService mess) {
		messagingService = mess;
		messagingService.addListener(new MeetingMessageListener());
		messagingService.start();
	}
	
	public void setExpiredMeetingCleanupTimerTask(ExpiredMeetingCleanupTimerTask c) {
		cleaner = c;
		cleaner.setMeetingService(this);
		cleaner.start();
	}
	
	/**
	 * Class that listens for messages from bbb-apps.
	 * @author Richard Alam
	 *
	 */
	private class MeetingMessageListener implements MessageListener {
		@Override
		public void meetingStarted(String meetingId) {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				log.debug("Setting meeting started time");
				m.setStartTime(System.currentTimeMillis());
			}
		}

		@Override
		public void meetingEnded(String meetingId) {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				log.debug("Setting meeting end time");
				m.setEndTime(System.currentTimeMillis());
			}
		}

		@Override
		public void userJoined(String meetingId, String userId, String name, String role) {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				User user = new User(userId, name, role);
				m.userJoined(user);
				log.debug("New user in meeting:" + user.getFullname());
			}
		}

		@Override
		public void userLeft(String meetingId, String userId) {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				User user = m.userLeft(userId);
				log.debug("User removed from meeting:" + user.getFullname());
			}
		}
		
		@Override
		public void updatedStatus(String meetingId, String userId, String status, String value) {
			Meeting m = getMeeting(meetingId);
			if (m != null) {
				User user = m.getUserById(userId);
				user.setStatus(status, value);
				log.debug("Setting new status value for participant:"+user.getFullname());
			}
		}
	}
	
}