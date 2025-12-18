package org.nextme.monitoringserver.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GrafanaAlert {

	private String receiver;
	private String status;
	private List<Alert> alerts;

	@JsonProperty("groupLabels")
	private Map<String, String> groupLabels;

	@JsonProperty("commonLabels")
	private Map<String, String> commonLabels;

	@JsonProperty("commonAnnotations")
	private Map<String, String> commonAnnotations;

	@JsonProperty("externalURL")
	private String externalURL;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Alert {
		private String status;
		private Map<String, String> labels;
		private Map<String, String> annotations;

		@JsonProperty("startsAt")
		private String startsAt;

		@JsonProperty("endsAt")
		private String endsAt;

		@JsonProperty("generatorURL")
		private String generatorURL;

		@JsonProperty("fingerprint")
		private String fingerprint;
	}
}
