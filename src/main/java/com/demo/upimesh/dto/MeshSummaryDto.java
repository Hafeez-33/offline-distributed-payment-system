package com.demo.upimesh.dto;

import java.util.List;
import java.util.Set;

/**
 * Summarized view of the mesh topology for regular polling.
 * Contains only lightweight node metadata and severed links.
 */
public record MeshSummaryDto(
        List<DeviceSummary> devices,
        Set<String> severedLinks,
        boolean allConverged
) {
    public record DeviceSummary(
            String deviceId,
            boolean hasInternet,
            String stateDigest,
            int packetCount
    ) {}
}
