package org.nextme.monitoringserver.event;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringNotificationEvent {

	private List<String> slackUserIds;
	private String message;
	private String actionId;
	private String actionValue;

	public MonitoringNotificationEvent(List<String> slackUserIds, String message) {
		this(slackUserIds, message, null, null);
	}
}
