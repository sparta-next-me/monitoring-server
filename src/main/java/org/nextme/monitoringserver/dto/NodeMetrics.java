package org.nextme.monitoringserver.dto;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NodeMetrics {

	private String nodeName;
	private Instant timestamp;

	// CPU
	private Double cpuUsage;

	// Memory
	private Double memoryUsage;
	private Double memoryTotal;
	private Double memoryUsagePercent;

	// Disk
	private Double diskUsage;
	private Double diskTotal;
	private Double diskUsagePercent;

	// Network
	private Double networkReceiveBytes;
	private Double networkTransmitBytes;
	private Double networkReceiveErrors;
	private Double networkTransmitErrors;
}
