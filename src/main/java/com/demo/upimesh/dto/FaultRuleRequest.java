package com.demo.upimesh.dto;

/**
 * Request DTO for registering a new fault rule in the simulator.
 */
public class FaultRuleRequest {

    private String faultType;
    private String sourceNode = "*";
    private String destinationNode = "*";
    private Integer occurrenceLimit = 1;
    private Long delayMs = 0L;
    private String packetHash;

    public FaultRuleRequest() {}

    public FaultRuleRequest(String faultType, String sourceNode, String destinationNode,
                            Integer occurrenceLimit, Long delayMs, String packetHash) {
        this.faultType = faultType;
        this.sourceNode = sourceNode != null ? sourceNode : "*";
        this.destinationNode = destinationNode != null ? destinationNode : "*";
        this.occurrenceLimit = occurrenceLimit != null ? occurrenceLimit : 1;
        this.delayMs = delayMs != null ? delayMs : 0L;
        this.packetHash = packetHash;
    }

    public String getFaultType() { return faultType; }
    public void setFaultType(String faultType) { this.faultType = faultType; }

    public String getSourceNode() { return sourceNode; }
    public void setSourceNode(String sourceNode) { this.sourceNode = sourceNode; }

    public String getDestinationNode() { return destinationNode; }
    public void setDestinationNode(String destinationNode) { this.destinationNode = destinationNode; }

    public Integer getOccurrenceLimit() { return occurrenceLimit; }
    public void setOccurrenceLimit(Integer occurrenceLimit) { this.occurrenceLimit = occurrenceLimit; }

    public Long getDelayMs() { return delayMs; }
    public void setDelayMs(Long delayMs) { this.delayMs = delayMs; }

    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }
}
