package org.nextme.monitoringserver.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContainerMetrics {

	private String containerName;
	private Double cpuUsage;
	private Double memoryUsageMB;
	private Double memoryLimitMB;
	private Double memoryUsagePercent;
}
