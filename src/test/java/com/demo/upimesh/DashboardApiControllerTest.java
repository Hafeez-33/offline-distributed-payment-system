package com.demo.upimesh;

import com.demo.upimesh.dto.FaultRuleRequest;
import com.demo.upimesh.fault.FaultInjector;
import com.demo.upimesh.service.InvariantAuditService;
import com.demo.upimesh.service.MeshSimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
public class DashboardApiControllerTest {

    @Autowired private WebApplicationContext wac;
    private MockMvc mvc;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private FaultInjector faultInjector;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        faultInjector.reset();
        mesh.healAll();
    }

    @Test
    void testDashboardOverview() throws Exception {
        mvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemStatus", notNullValue()))
                .andExpect(jsonPath("$.totalDevices", is(5)))
                .andExpect(jsonPath("$.onlineBridges", is(1)))
                .andExpect(jsonPath("$.meshConverged", notNullValue()))
                .andExpect(jsonPath("$.totalAccounts", greaterThanOrEqualTo(1)));
    }

    @Test
    void testMeshSummary() throws Exception {
        mvc.perform(get("/api/dashboard/mesh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(5)))
                .andExpect(jsonPath("$.devices[0].deviceId", notNullValue()))
                .andExpect(jsonPath("$.devices[0].stateDigest", notNullValue()))
                .andExpect(jsonPath("$.severedLinks", notNullValue()));
    }

    @Test
    void testDeviceDetail() throws Exception {
        mvc.perform(get("/api/dashboard/mesh/devices/phone-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId", is("phone-alice")))
                .andExpect(jsonPath("$.stateDigest", notNullValue()))
                .andExpect(jsonPath("$.bucketChecksums", hasSize(16)));

        mvc.perform(get("/api/dashboard/mesh/devices/non-existent-device"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testWalletsSummary() throws Exception {
        mvc.perform(get("/api/dashboard/wallets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallets", notNullValue()))
                .andExpect(jsonPath("$.accounts", notNullValue()));
    }

    @Test
    void testTransactionsPaginated() throws Exception {
        mvc.perform(get("/api/dashboard/transactions?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.content", notNullValue()));
    }

    @Test
    void testReliabilityReportWithInvariants() throws Exception {
        mvc.perform(get("/api/dashboard/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics", notNullValue()))
                .andExpect(jsonPath("$.invariants", hasSize(12)))
                .andExpect(jsonPath("$.invariants[0].id", is("I1")))
                .andExpect(jsonPath("$.invariants[0].status", notNullValue()));
    }

    @Test
    void testFaultRuleLifecycleAndValidation() throws Exception {
        // 1. Invalid fault type -> 400
        mvc.perform(post("/api/faults/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"INVALID_FAULT\",\"occurrenceLimit\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Unknown faultType")));

        // 2. Invalid limit (< 1) -> 400
        mvc.perform(post("/api/faults/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"DROP\",\"occurrenceLimit\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("occurrenceLimit")));

        // 3. Invalid node -> 400
        mvc.perform(post("/api/faults/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"DROP\",\"sourceNode\":\"unknown-phone\",\"occurrenceLimit\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Unknown sourceNode")));

        // 4. Invalid packet hash -> 400
        mvc.perform(post("/api/faults/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"DROP\",\"occurrenceLimit\":1,\"packetHash\":\"short-hash\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("packetHash")));

        // 5. Valid rule -> 200
        String res = mvc.perform(post("/api/faults/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"TRANSIENT_DATABASE_FAILURE\",\"occurrenceLimit\":2,\"delayMs\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RULE_REGISTERED")))
                .andExpect(jsonPath("$.faultId", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        String faultId = res.split("\"faultId\":\"")[1].split("\"")[0];

        // 6. List rules
        mvc.perform(get("/api/faults/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].faultId", is(faultId)))
                .andExpect(jsonPath("$[0].faultType", is("TRANSIENT_DATABASE_FAILURE")));

        // 7. Delete specific rule
        mvc.perform(delete("/api/faults/rule/" + faultId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RULE_DELETED")));

        // 8. Delete again -> 404
        mvc.perform(delete("/api/faults/rule/" + faultId))
                .andExpect(status().isNotFound());

        // 9. Reset faults
        mvc.perform(post("/api/faults/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FAULTS_RESET")));
    }
}
