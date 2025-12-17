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

	public PrometheusClient(@Value("${prometheus.url}") String prometheusUrl) {
		this.webClient = WebClient.builder()
			.baseUrl(prometheusUrl)
			.build();
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
	노드 내 모든 컨테이너의 리소스 사용률 조회
	@param nodeName 노드 이름
	@return 컨테이너별 메트릭 맵 (컨테이너명 -> 메트릭)
	 */
	public Map<String, ContainerMetrics> getContainerMetrics(String nodeName) {
		log.info("Fetching container metrics for node: {}", nodeName);

		Map<String, ContainerMetrics> containerMetricsMap = new HashMap<>();

		try {
			// 컨테이너별 CPU 사용률 조회
			String cpuQuery = String.format(
				"rate(container_cpu_usage_seconds_total{instance=~\".*%s.*\",container!=\"\",container!=\"POD\"}[5m]) * 100",
				nodeName
			);

			JsonNode cpuResponse = executeRangeQuery(cpuQuery);

			if (cpuResponse != null) {
				JsonNode results = cpuResponse.path("data").path("result");

				for (JsonNode result : results) {
					String containerName = result.path("metric").path("container").asText();

					if (!containerName.isEmpty()) {
						Double cpuUsage = 0.0;
						JsonNode values = result.path("value");
						if (values.size() > 1) {
							cpuUsage = Double.parseDouble(values.get(1).asText());
						}

						// 메모리 사용률 조회
						Double memoryUsageMB = queryContainerMemory(nodeName, containerName);

						ContainerMetrics metrics = ContainerMetrics.builder()
							.containerName(containerName)
							.cpuUsage(cpuUsage)
							.memoryUsageMB(memoryUsageMB)
							.build();

						containerMetricsMap.put(containerName, metrics);
					}
				}
			}

			log.info("Found {} containers", containerMetricsMap.size());

		} catch (Exception e) {
			log.error("Failed to fetch container metrics", e);
		}

		return containerMetricsMap;
	}

	// CPU Usage 조회
	private Double queryCpuUsage(String nodeName, Instant timestamp) {
		String query = String.format(
			"100 - (avg(rate(node_cpu_seconds_total{mode=\"idle\",instance=~\".*%s.*\"}[5m])) * 100)",
			nodeName
		);
		return executeQuery(query, timestamp);
	}

	// Memory Usage 조회
	private Double queryMemoryUsage(String nodeName, Instant timestamp) {
		String query = String.format(
			"100 - ((node_memory_MemAvailable_bytes{instance=~\".*%s.*\"}/node_memory_MemTotal_bytes{instance=~\".*%s.*\"}) * 100)",
			nodeName, nodeName
		);
		return executeQuery(query, timestamp);
	}

	// Disk Usage 조회
	private Double queryDiskUsage(String nodeName, Instant timestamp) {
		String query = String.format(
			"100 - ((node_filesystem_avail_bytes{instance=~\".*%s.*\",fstype!=\"tmpfs\"} / node_filesystem_size_bytes{instance=~\".*%s.*\",fstype!=\"tmpfs\"}) * 100)",
			nodeName, nodeName
		);
		return executeQuery(query, timestamp);
	}

	// 특정 컨테이너의 메모리 사용량 조회
	private Double queryContainerMemory(String nodeName, String containerName) {
		String query = String.format(
			"container_memory_usage_bytes{instance=~\".*%s.*\",container=\"%s\"} / 1024 / 1024",
			nodeName, containerName
		);

		Double memoryMB = executeQuery(query, Instant.now());
		return memoryMB != null ? memoryMB : 0.0;
	}

	// Range Query 실행
	private JsonNode executeRangeQuery(String query) {
		try {
			return webClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/api/v1/query")
					.queryParam("query", query)
					.build())
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
			JsonNode response = webClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/api/v1/query")
					.queryParam("query", query)
					.queryParam("time", timestamp.getEpochSecond())
					.build())
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
