package org.nextme.monitoringserver.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.nextme.monitoringserver.dto.ContainerMetrics;
import org.nextme.monitoringserver.dto.NodeMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PrometheusClient {

	private final WebClient webClient;
	private final String prometheusUrl;

	public PrometheusClient(@Value("${prometheus.url}") String prometheusUrl) {
		this.prometheusUrl = prometheusUrl;
		this.webClient = WebClient.builder().build();
	}

	/*
	특정 노드의 과거 메트릭 조회
	@param nodeName 노드 이름
	@param hours 조회할 과거 시간
	@return 노드 메트릭 리스트
	 */
	public List<NodeMetrics> getNodeMetricsHistory(String nodeName, int hours) {
		log.info("Fetching metrics for node: {}, last {} hours", nodeName, hours);
		List<NodeMetrics> metricsList = new ArrayList<>();

		try {
			for (int i = 0; i < hours; i++) {
				Instant timestamp = Instant.now().minusSeconds(3600L * i);

				NodeMetrics metrics = NodeMetrics.builder()
					.nodeName(nodeName)
					.timestamp(timestamp)
					.cpuUsage(queryCpuUsage(nodeName, timestamp))
					.memoryUsagePercent(queryMemoryUsage(nodeName, timestamp))
					.diskUsagePercent(queryDiskUsage(nodeName, timestamp))
					.build();

				metricsList.add(metrics);
			}

			log.info("Successfully fetched {} metrics data points", metricsList.size());
		} catch (Exception e) {
			log.error("Failed to fetch metrics from Prometheus", e);
		}

		return metricsList;
	}

	/*
	노드 내 모든 서비스의 리소스 사용률 조회 (JVM 메트릭 기반)
	@param nodeName 노드 이름 (사용하지 않음, 모든 서비스 조회)
	@return 서비스별 메트릭 맵 (서비스명 -> 메트릭)
	 */
	public Map<String, ContainerMetrics> getContainerMetrics(String nodeName) {
		log.info("Fetching service metrics (JVM-based)");

		Map<String, ContainerMetrics> serviceMetricsMap = new HashMap<>();

		try {
			// JVM 메모리 사용량 조회 (heap 영역만)
			// 이스케이프 없이 직접 작성
			String memoryQuery = "sum(jvm_memory_used_bytes{area=\"heap\"}) by (instance)";

			log.info("Executing memory query: {}", memoryQuery);

			JsonNode memoryResponse = executeRangeQuery(memoryQuery);

			if (memoryResponse != null) {
				JsonNode results = memoryResponse.path("data").path("result");

				log.debug("Memory query returned {} results", results.size());

				for (JsonNode result : results) {
					String serviceName = result.path("metric").path("instance").asText();

					if (!serviceName.isEmpty()) {
						JsonNode values = result.path("value");
						Double memoryBytes = 0.0;
						if (values.size() > 1) {
							memoryBytes = Double.parseDouble(values.get(1).asText());
						}
						Double memoryMB = memoryBytes / 1024 / 1024;

						// CPU 사용률 조회 (프로세스 CPU)
						Double cpuUsage = queryServiceCpu(serviceName);

						ContainerMetrics metrics = ContainerMetrics.builder()
							.containerName(serviceName)
							.cpuUsage(cpuUsage)
							.memoryUsageMB(memoryMB)
							.build();

						serviceMetricsMap.put(serviceName, metrics);
						log.debug("Added service: {} - CPU: {}%, Memory: {} MB",
							serviceName, cpuUsage, String.format("%.2f", memoryMB));
					}
				}
			} else {
				log.warn("Memory query returned null response");
			}

			log.info("Found {} services with metrics", serviceMetricsMap.size());

		} catch (Exception e) {
			log.error("Failed to fetch service metrics", e);
		}

		return serviceMetricsMap;
	}

	// CPU Usage 조회
	private Double queryCpuUsage(String nodeName, Instant timestamp) {
		String query = "100 - (avg(rate(node_cpu_seconds_total{mode=\"idle\",instance=~\".*" + nodeName + ".*\"}[5m])) * 100)";
		return executeQuery(query, timestamp);
	}

	// Memory Usage 조회
	private Double queryMemoryUsage(String nodeName, Instant timestamp) {
		String query = "100 - ((node_memory_MemAvailable_bytes{instance=~\".*" + nodeName + ".*\"}/node_memory_MemTotal_bytes{instance=~\".*" + nodeName + ".*\"}) * 100)";
		return executeQuery(query, timestamp);
	}

	// Disk Usage 조회
	private Double queryDiskUsage(String nodeName, Instant timestamp) {
		String query = "100 - ((node_filesystem_avail_bytes{instance=~\".*" + nodeName + ".*\",fstype!=\"tmpfs\"} / node_filesystem_size_bytes{instance=~\".*" + nodeName + ".*\",fstype!=\"tmpfs\"}) * 100)";
		return executeQuery(query, timestamp);
	}

	// 특정 서비스의 CPU 사용률 조회 (프로세스 CPU)
	private Double queryServiceCpu(String serviceName) {
		// process_cpu_usage는 0.0~1.0 범위이므로 100을 곱해 퍼센트로 변환
		String query = "process_cpu_usage{instance=\"" + serviceName + "\"} * 100";

		Double cpuUsage = executeQuery(query, Instant.now());
		return cpuUsage != null ? cpuUsage : 0.0;
	}

	// Range Query 실행
	private JsonNode executeRangeQuery(String query) {
		try {
			String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
			String fullUrl = prometheusUrl + "/api/v1/query?query=" + encodedQuery;

			log.debug("Executing Prometheus range query: {}", query);

			return webClient.get()
				.uri(java.net.URI.create(fullUrl))
				.retrieve()
				.bodyToMono(JsonNode.class)
				.block();

		} catch (Exception e) {
			log.warn("Failed to execute range query: {}", query, e);
			return null;
		}
	}

	// Prometheus API 쿼리 실행
	private Double executeQuery(String query, Instant timestamp) {
		try {
			String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
			String fullUrl = prometheusUrl + "/api/v1/query?query=" + encodedQuery + "&time=" + timestamp.getEpochSecond();

			log.debug("Executing query: {} at time: {}", query, timestamp.getEpochSecond());

			JsonNode response = webClient.get()
				.uri(java.net.URI.create(fullUrl))
				.retrieve()
				.bodyToMono(JsonNode.class)
				.block();

			if (response != null && response.path("status").asText().equals("success")) {
				JsonNode result = response.path("data").path("result");
				if (result.isArray() && result.size() > 0) {
					String value = result.get(0).path("value").get(1).asText();
					return Double.parseDouble(value);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to execute query: {}", query, e);
		}

		return 0.0;
	}
}
