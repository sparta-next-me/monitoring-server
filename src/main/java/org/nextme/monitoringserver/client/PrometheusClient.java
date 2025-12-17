package org.nextme.monitoringserver.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
