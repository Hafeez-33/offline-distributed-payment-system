# Offline Distributed Payment System

## Long-Term Engineering Plan

> **Status:** Active Development
> **Current Phase:** Phase 8 — Observability, Metrics & Production Diagnostics (COMPLETED)
> **Primary Backend:** Java 17 + Spring Boot + PostgreSQL 16 + Redis 7 + Micrometer / Prometheus
> **Database Migration:** Flyway
> **Target Frontend:** React + TypeScript (Phase 6 COMPLETED)
> **Target Mobile:** Android / Kotlin
> **Architecture Goal:** Secure, offline-first, distributed payment protocol prototype

---

# 1. Project Vision

## 1.1 What are we building?

We are building an **offline-first distributed payment system prototype inspired by UPI-style digital payments**.

The system is designed to demonstrate how authenticated payment transactions could propagate between devices when the devices do not have continuous Internet connectivity.

The core idea is:

```text
Offline Device
      │
      │ Local payment
      ▼
Nearby Peer
      │
      │ Gossip / forwarding
      ▼
Another Peer
      │
      ▼
Internet Bridge
      │
      │ Later synchronization
      ▼
Authoritative Backend
      │
      ▼
Validation + Reconciliation
      │
      ▼
Settlement / Ledger
```

The project is a **research/engineering prototype**, not a production replacement for the UPI infrastructure.

The goal is to demonstrate strong engineering concepts around:

* Distributed systems
* Cryptography
* Offline-first architecture
* Eventual consistency
* Idempotency
* Secure transaction authorization
* Gossip protocols
* Anti-entropy synchronization
* Conflict resolution
* Double-spending mitigation
* Concurrency
* Fault tolerance
* Observability

---

# 2. Why This Project Exists

Most payment systems assume that a client can communicate with a central authority during a transaction.

An offline payment system introduces a much harder problem:

> How can a device create and transfer a trustworthy representation of monetary value when it cannot immediately communicate with the authoritative ledger?

This project explores that problem.

The project should therefore NOT become just:

```text
CRUD application
+
payment form
+
database
```

Instead, it should demonstrate:

```text
Security
    +
Distributed Systems
    +
Consistency
    +
Fault Tolerance
    +
Offline Computing
    +
Observability
```

---

# 3. Current Repository Baseline

The original repository already contains a Spring Boot prototype with:

* Java 17
* Spring Boot
* REST APIs
* Thymeleaf dashboard
* H2 database
* JPA
* AES-256-GCM encryption
* RSA-OAEP key wrapping
* SHA-256 hashing
* Idempotency using ConcurrentHashMap
* Database packet-hash uniqueness
* Optimistic locking
* Virtual mesh devices
* TTL-based packet propagation
* Simulated gossip
* Bridge ingestion
* Transaction settlement
* Basic concurrency tests
* Tamper detection tests

Current simplified architecture:

```text
                 Spring Boot
                     │
       ┌─────────────┼──────────────┐
       │             │              │
     Crypto        Mesh          Settlement
       │             │              │
       │         Virtual Nodes       │
       │             │              │
       └─────────────┼──────────────┘
                     │
                    H2
```

This existing implementation is the **starting point**.

We should preserve useful existing functionality and evolve it incrementally rather than rewriting the project from scratch.

---

# 4. Current Known Limitations

The baseline analysis identified the following major limitations.

## Security

* No sender digital signature
* Sender identity is not cryptographically authenticated
* No public-key registry
* Ingestion APIs are not authenticated
* PIN hash is not actually used for authorization
* Server RSA key is ephemeral
* H2 console is enabled in runtime configuration

## Distributed Systems

* Gossip is only simulated
* Network topology is fully connected
* No realistic peer discovery
* No anti-entropy synchronization
* No causal ordering
* No vector clocks
* No conflict-resolution protocol
* No distributed idempotency store
* No real network partition simulation

## Offline Payments

* No cryptographically authorized offline spending capability
* No offline wallet model
* No strong double-spending mitigation
* No transaction receipt propagation
* No offline balance certificate
* No monotonic wallet counter

## Reliability

* Idempotency claim can remain permanently claimed after a transient processing failure
* No retry/unclaim mechanism
* Optimistic-lock failures do not have retry/backoff handling
* In-memory idempotency state prevents horizontal scaling

## Storage

* H2 in-memory database
* No PostgreSQL
* No durable distributed cache
* No production persistence strategy

## Frontend

* Existing UI is a demonstration dashboard
* No React application
* No real-time WebSocket/SSE event stream
* No interactive mesh topology
* No transaction explorer
* No security monitoring dashboard

## Testing

Existing testing is limited.

Missing areas include:

* Replay attack tests
* Clock-skew tests
* Signature tests
* Concurrent account contention
* Gossip TTL tests
* Network partition tests
* Packet loss tests
* Duplicate packet tests
* Node failure tests
* Double-spending scenarios
* Load testing
* Fault injection

---

# 5. Target Architecture

The long-term architecture should evolve toward:

```text
                         ┌─────────────────────┐
                         │   React Frontend     │
                         │   TypeScript         │
                         └──────────┬──────────┘
                                    │
                         REST / WebSocket / SSE
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │       Spring Boot API       │
                    └──────────────┬──────────────┘
                                   │
              ┌────────────────────┼───────────────────┐
              │                    │                   │
              ▼                    ▼                   ▼
        Authentication         Payment Core       Mesh Engine
              │                    │                   │
              ▼                    ▼                   ▼
        Key Registry          Idempotency          Gossip
                                   │               Anti-Entropy
                                   │               Reconciliation
                                   ▼
                              Settlement
                                   │
                         ┌─────────┴─────────┐
                         ▼                   ▼
                    PostgreSQL             Redis
                         │                   │
                         ▼                   ▼
                      Ledger            Distributed
                                      Idempotency
                         
                                   │
                                   ▼
                            Observability
                         Prometheus + Grafana
```

Long-term mobile architecture:

```text
Android Device
      │
      ├── Secure Identity
      ├── Local Wallet
      ├── Local Ledger
      ├── Transaction Queue
      ├── BLE
      └── Gossip Engine
             │
             ▼
       Nearby Devices
             │
             ▼
        Bridge Device
             │
             ▼
        Backend Server
```

---

# 6. Core Design Principles

## Principle 1 — Security before convenience

Never trust:

* Sender fields
* Client-provided balances
* Client-provided public keys
* Client-provided transaction status
* Client-provided authorization

Every security-sensitive claim must be independently verified.

---

## Principle 2 — Encryption is not authentication

Encryption answers:

> Can someone read the message?

Digital signatures answer:

> Did the owner of the private key authorize the message?

The system needs both.

---

## Principle 3 — Idempotency is not double-spend prevention

Idempotency prevents:

```text
Same transaction
      ↓
processed twice
```

It does NOT prevent:

```text
Transaction A → ₹500 → Bob

Transaction B → ₹500 → Charlie
```

when both transactions are different but spend the same underlying value.

These are separate problems and must be treated separately.

---

## Principle 4 — Offline systems must define consistency guarantees

During network partition:

```text
Device A
    X
Device B
```

the system cannot assume immediate global knowledge.

Therefore we must explicitly define:

* What can be committed offline?
* What can be temporarily accepted?
* What happens during reconciliation?
* How are conflicts detected?
* Which state becomes authoritative?
* How is double spending handled?

---

## Principle 5 — Agents must work incrementally

Antigravity must NOT attempt to build the entire project in one operation.

Each phase must follow:

```text
Understand
    ↓
Design
    ↓
Implement
    ↓
Test
    ↓
Review
    ↓
Document
    ↓
Commit
    ↓
Pull Request
```

---

# 7. Development Workflow

Git branches:

```text
main
 │
 │ production-ready
 │
dev
 │
 ├── feature/cryptographic-identity
 ├── feature/reliable-idempotency
 ├── feature/offline-wallet
 ├── feature/gossip-v2
 ├── feature/fault-injection
 ├── feature/react-dashboard
 ├── feature/production-storage
 └── feature/android-ble
```

Rules:

1. Never develop directly on `main`.
2. Never develop directly on `dev`.
3. Every feature gets its own `feature/*` branch.
4. Run tests before committing.
5. Review agent-generated changes.
6. Push only the feature branch.
7. Create a Pull Request into `dev`.
8. Merge into `dev` only after review.
9. `main` represents stable/production-ready milestones.

---

# 8. Development Phases

---

## PHASE 0 — Repository Understanding & Baseline

### Objective

Understand the existing project before modifying it.

### Completed

* Repository forked
* Repository cloned
* Own GitHub remote configured
* Original repository configured as upstream
* `dev` branch created
* Repository inspected
* Implementation readiness report generated

### Deliverable

A clear understanding of:

```text
Current architecture
Current security
Current transaction lifecycle
Current distributed simulation
Current testing
Current limitations
```

### Status

**COMPLETED**

---

# PHASE 1 — Cryptographic Identity & Transaction Authorization

## Objective

Prove that the sender actually authorized the payment.

### Current problem

The system encrypts the payment but does not cryptographically authenticate the sender.

### Build

Each account receives:

```text
Ed25519 Private Key
Ed25519 Public Key
```

The server stores the authoritative public key.

The sender signs:

```text
senderVpa
receiverVpa
amount
nonce
signedAt
```

using a deterministic canonical representation.

### Verification

```text
Payment
   │
   ▼
Decrypt
   │
   ▼
Resolve sender account
   │
   ▼
Retrieve registered public key
   │
   ▼
Verify Ed25519 signature
   │
   ├── INVALID → reject
   │
   └── VALID
          │
          ▼
       Continue
```

### Tests

* Valid signature
* Invalid signature
* Modified amount
* Modified receiver
* Modified sender
* Modified nonce
* Modified timestamp
* Wrong account signature
* Unknown sender
* Tampered ciphertext
* Existing idempotency tests

### Status

**COMPLETED**

---

# PHASE 2 — Reliable Idempotency & Transaction Processing

## Objective

Make payment processing safe under retries, failures, and concurrent delivery.

### Problem Addressed

In the earlier architecture, an in-memory idempotency claim was acquired before settlement. If a transient database transaction failure or optimistic-lock collision occurred, the ledger rolled back, but the in-memory claim remained in the cache. Subsequent legitimate retries of the same packet were falsely rejected as duplicates (`DUPLICATE_DROPPED`).

### Implemented Architecture & Guarantees

1. **Authoritative Deduplication Barrier**:
   The relational database `UNIQUE` constraint on `Transaction.packetHash` is the definitive source of truth. The in-memory map acts purely as an in-flight concurrency gate and performance fast-path, never replacing database authority.

2. **In-Flight Concurrency Gate**:
   `IdempotencyService` manages thread-safe claims via `tryAcquire(packetHash)` and explicit `release(packetHash)` on transient failures, validation rejections, or rollbacks. Completed transactions are retained via `markCompleted(packetHash)`.

3. **Transaction-Safe Optimistic-Lock Retry**:
   `SettlementService` executes bounded retries (up to 3 attempts with exponential backoff and jitter: ~25ms, ~50ms) where each attempt executes in an isolated, independent transaction (`PROPAGATION_REQUIRES_NEW`).

4. **Lost-Response Recovery (Recovery Path)**:
   `BridgeIngestionService` checks `TransactionRepository.findByPacketHash(hash)` before processing. If a previously committed transaction exists (e.g. from an HTTP timeout or lost response), it immediately returns the original committed transaction result (`SETTLED` or `REJECTED`) without performing duplicate debit/credit ledger operations.

5. **Deterministic Failure Classification**:
   - **Permanent Validation Failure** (`invalid_signature`, `stale_packet`, `unknown_sender`, etc.): In-flight claim is released, transaction is rejected as `INVALID`.
   - **Permanent Business Rejection** (`insufficient_balance`): Persisted as `REJECTED` transaction in the authoritative database, completed state retained, no retry.
   - **Transient Transaction Failure** (`OptimisticLockException`, DB timeout after retry exhaustion): In-flight claim is released, returns `TRANSIENT_FAILURE` (`transient_settlement_failure`), enabling mesh bridges to retry.

### Tests and Results

Comprehensive automated test suite implemented in `ReliableIdempotencyTest.java` (13 test cases):
1. `transientFailureFollowedBySuccessfulRetry` — PASS
2. `optimisticLockConflictFollowedBySuccessfulRetry` — PASS
3. `concurrentDuplicateDelivery` — PASS
4. `successfulPacketRetriedAfterCompletion` — PASS
5. `insufficientBalanceIsPermanentlyRejected` — PASS
6. `invalidSignatureIsPermanentlyRejected` — PASS
7. `stalePacketIsPermanentlyRejected` — PASS
8. `rollbackReleasesInFlightClaim` — PASS
9. `lostHttpResponseFollowedByDuplicateRetry` — PASS
10. `concurrentDifferentPaymentsPreserveCorrectBalances` — PASS (Alice → Bob ₹100, Alice → Carol ₹200, Alice → Dave ₹300 concurrently with optimistic lock resolution)
11. `retryExhaustionProducesTransientFailure` — PASS
12. `noPacketCanSettleTwice` — PASS
13. `tenConcurrentIdenticalPacketSubmissionsProduceOneSettlement` — PASS (10 concurrent threads, exactly 1 settlement, 9 duplicates dropped)

Total Test Suite: 36 tests run, 0 failures, 0 errors.

### Status

**COMPLETED**

---

# PHASE 3 — Offline Wallet & Double-Spending Mitigation

## Objective

Solve the fundamental offline-payment problem.

### Problem

Alice has:

```text
₹1000
```

while offline.

She could attempt:

```text
Alice → Bob     ₹1000
Alice → Charlie ₹1000
```

before either transaction reaches the server.

Different transaction IDs do not solve this.

### Implemented Architecture & Non-Negotiable Security Model

1. **Non-Negotiable Security Guarantees**:
   - **No absolute prevention claim**: We explicitly do NOT claim "Offline double spending is completely prevented."
   - **Bounded Exposure**: The server-authorized offline allocation strictly bounds the issuer's authorized offline exposure.
   - **Identical replay**: Prevented by Phase 2 authoritative packet hashing and database unique constraint.
   - **Fork Detection**: Conflicting offline spends using duplicate sequence counters are detected during reconciliation.
   - **Audit and Freezing**: Conflicting wallet histories are immediately frozen in `LOCKED_DISPUTED` state with auditable records.
   - **Hardware Boundary**: Software-only devices cannot provide absolute physical anti-cloning guarantees; production rollback resistance requires hardware-backed keys and monotonic state (e.g. Android StrongBox / eSE).

2. **Escrow Accounting**:
   - Invariant: `Account total funds = liquid available balance + offline locked balance`.
   - Allocation: ₹X debited from liquid balance, credited to `offlineLockedBalance`, `OfflineWallet` created with signed `OfflineWalletCertificate`.
   - Settlement: Deducted from sender's `offlineLockedBalance` and credited to recipient's liquid balance. Sender's online liquid balance is never debited twice.
   - Reconciliation/Expiry: Unused escrow (`allocatedAmount - settledAmount`) is returned to the sender's liquid balance.

3. **Server-Signed Offline Wallet Certificate**:
   - `OfflineWalletCertificate` record includes `walletId`, `ownerVpa`, `ownerPublicKey`, `allocatedAmount`, `walletEpoch`, `validFrom`, `validUntil`, `initialCounter`, and `issuerSignature`.
   - Deterministic canonical bytes signed by server's Ed25519 issuer key.
   - Cryptographically verified on backend: signature, validity window, wallet owner, key binding, epoch, status, and allocation limit.

4. **Sequence State Machine & Fork Detection**:
   - `counter == lastSettledCounter + 1`: In-order settlement with automatic cascading of staged sequence gaps.
   - `counter > lastSettledCounter + 1`: Staged in `PENDING_SEQUENCE_GAP` status up to configurable gap window (default 30 min). If window expires without missing counter, marked `REJECTED_UNRESOLVED_SEQUENCE_GAP` and wallet marked `AUDIT_REQUIRED`.
   - `counter <= lastSettledCounter`: If packet hash matches, Phase 2 duplicate recovery returns committed record; if packet hash differs, flags `CONFLICTING` (`double_spend_counter_collision`), marks wallet `LOCKED_DISPUTED`, and links conflicting transaction to winning transaction.
   - Authoritative Winner Policy: "Among conflicting transactions that reach the authoritative backend, the first valid transaction to commit is the settlement winner."

5. **Epoch Semantics & Terminal Transfer Policy**:
   - Strictly increasing wallet epochs ($E_1, E_2, \dots$); at most one ACTIVE epoch per wallet.
   - Obsolete epoch transactions are rejected with `obsolete_wallet_epoch`.
   - Terminal Transfer Policy: Payer $\to$ Payee $\to$ Backend. Payee cannot re-spend offline wallet funds.

6. **Signed Settlement Receipt**:
   - Backend generates a deterministic `SettlementReceipt` (`transactionId`, `packetHash`, `counter`, `status`, `settledAt`) signed with server's Ed25519 issuer key.

### Tests and Results

Automated test suite implemented in `OfflineWalletReliabilityTest.java` (20 test cases):
1. `validOfflineAllocationEscrowsFunds` — PASS
2. `spendingWithinAllocationSucceeds` — PASS
3. `outOfOrderCounterArrivalTriggersSequenceGap` — PASS
4. `missingCounterGapEventuallyResolved` — PASS
5. `gapTimeoutRejectsPendingTransaction` — PASS
6. `oldEpochCertificateIsRejected` — PASS
7. `expiredCertificateIsRejected` — PASS
8. `unusedEscrowReturnedOnReconciliation` — PASS
9. `escrowCorrectlyReducedAfterSettlement` — PASS
10. `conflictingCounterFreezesWallet` — PASS
11. `twoConflictingTransactionsBeforeReconciliation` — PASS
12. `clonedWalletStateDetectedOnSync` — PASS
13. `walletReissuanceAfterEpochIncrement` — PASS
14. `spendingAboveAllocationIsRejected` — PASS
15. `forgedWalletCertificateIsRejected` — PASS
16. `tamperedTransactionAmountFailsSignature` — PASS
17. `tamperedRecipientFailsSignature` — PASS
18. `reusedPacketHashFollowsPhase2Idempotency` — PASS
19. `terminalTransferRejectsRespentFunds` — PASS
20. `signedSettlementReceiptVerification` — PASS

Total Test Suite: 56 tests run, 0 failures, 0 errors.

### Status

**COMPLETED**

---

# PHASE 4 — Advanced Gossip & Distributed Synchronization

## Objective

Transform the basic simulated gossip into a robust hybrid distributed synchronization protocol supporting epidemic push, pairwise anti-entropy pull, deterministic state digests, partition resilience, and mathematical convergence.

### Implemented Architecture & Guarantees

1. **Authoritative Identity vs Transport Identifier**:
   - `packetHash = SHA-256(ciphertext)` is the authoritative cryptographic identity used for deduplication, state digests, bucket checksums, and sync requests.
   - `packetId` (UUID) serves strictly as an outer transport/message identifier.

2. **State Digest & Prefix-Bucket Slicing**:
   - Empty state: `stateDigest = SHA-256("EMPTY")`.
   - Populated state: full `packetHash` values are sorted lexicographically and hashed.
   - Equal SHA-256 state digests provide cryptographically strong practical equality with negligible collision probability ($\approx 2^{-256}$).
   - 16-bucket prefix checksum array groups packets by the first hex character (`0`–`f`) of their authoritative `packetHash`.

3. **Hybrid Push-Pull Protocol**:
   - **Epidemic Push**: Low-latency hop-by-hop forwarding decrements TTL. TTL strictly limits push broadcast radius.
   - **Anti-Entropy Pull**: Pairwise background synchronization operates independently of push TTL. TTL never blocks anti-entropy repair.

4. **Deterministic Anti-Entropy Sequence**:
   ```text
   STATE_SUMMARY
        ↓
   Compare root digest (O(1) summary exit on match)
        ↓
   Compare 16 prefix bucket checksums
        ↓
   Exchange full authoritative packet hashes for divergent buckets
        ↓
   Compute symmetric differences (missingFromPeer / missingFromSelf)
        ↓
   SYNC_REQUEST (bounded max 50 packets per batch)
        ↓
   SYNC_RESPONSE
        ↓
   SYNC_ACK (certifies pairwise completion)
   ```

5. **Failure & Partition Resilience**:
   - Simulated network link severing and submesh partitioning (`/api/mesh/partition`).
   - Healing (`/api/mesh/heal`) reconnects links and triggers mutual bi-directional anti-entropy reconciliation.
   - Alternate-peer selection: If a target peer times out or fails, the node aborts the session, marks the peer `DEGRADED`, and selects an alternate reachable neighbor.
   - Volatile restart: Restarting a simulator mesh node wipes its ephemeral in-memory buffer; it re-syncs all packets from peers without altering or reconstructing authoritative backend financial balances.

6. **Preservation of Phase 3 Double-Spending Invariants**:
   - Conflicting offline wallet transactions (same wallet ID and counter, different ciphertexts) both propagate through the mesh.
   - The mesh never discards either transaction as a conflict.
   - Phase 3 backend settlement remains the sole authoritative arbiter for conflict detection and freezing disputed wallets.

7. **Explicit Non-Goals**:
   - No linearizability or strong synchronous consistency (mesh is eventually consistent).
   - No global total transaction ordering (ordering is causal per wallet and authoritative at backend).
   - No real BLE/radio guarantees (software protocol simulation).
   - No production database migration (remains in-memory simulator; PostgreSQL/Redis deferred to Phase 7).
   - No hardware wallet guarantees (hardware-backed anti-cloning deferred to Phase 9).

### Tests and Results

Automated test suite implemented in `AdvancedGossipSyncTest.java` (15 test cases):
1. `identicalPeersProduceImmediateDigestMatchWithoutTransfers` — PASS
2. `singleMissingPacketRepairedViaAntiEntropy` — PASS
3. `biDirectionalMissingPacketsRepairedSimultaneously` — PASS
4. `duplicateSyncMessageSuppression` — PASS
5. `packetLossRecoveredBySubsequentAntiEntropy` — PASS
6. `delayedSyncResponseHandledWithoutDeadlock` — PASS
7. `alternatePeerSelectedWhenSyncTargetTimesOut` — PASS
8. `nodeRestartReSyncsBufferFromPeersWithoutAlteringBackend` — PASS
9. `networkPartitionMaintainsSubMeshConsistency` — PASS
10. `partitionHealTriggersCompleteBiDirectionalConvergence` — PASS
11. `concurrentTransactionsDuringPartitionSynchronizeOnHeal` — PASS
12. `prefixBucketDigestPinpointsDivergentSlices` — PASS
13. `mathematicalConvergenceAchievedAcrossAllDevices` — PASS
14. `ttlExhaustionDoesNotPreventAntiEntropyRepair` — PASS
15. `largeBatchSynchronizationRespectsPagingLimits` — PASS

Total Project Test Suite: 71 tests run, 0 failures, 0 errors.

### Status

**COMPLETED**

---

# PHASE 5 — Fault Injection & Distributed Reliability Testing

## Objective

Validate whether Phases 1–4 continue to preserve their security, idempotency, sequence, and financial conservation invariants under adverse distributed-system conditions using a deterministic, rule-based fault-injection framework.

### 1. Financial Safety Boundary
Fault injection operates **strictly at transport, control, and exception boundaries**. The fault-injection engine **never directly mutates** account balances, transaction amounts, escrow balances, wallet counters, or database records. All ledger mutations proceed solely through standard domain services (`SettlementService`, `OfflineWalletService`).

### 2. Supported Fault Types (`FaultType`)
The layer supports 13 discrete deterministic fault types:
- **`DROP`**: Discards packets or sync messages silently during transit.
- **`DUPLICATE`**: Injects duplicate delivery of the identical packet $N$ times.
- **`DELAY`**: Withholds packets from initial gossip push for delayed delivery.
- **`REORDER`**: Inverts transmission order (e.g. delivers counter 2 before counter 1) to verify existing Phase 3 `PENDING_SEQUENCE_GAP` handling without altering settlement logic.
- **`PARTITION`**: Severs communication links between submeshes or nodes.
- **`PEER_UNAVAILABLE`**: Simulates peer unavailability during anti-entropy to verify `syncWithFallback`.
- **`BRIDGE_UNAVAILABLE`**: Simulates mesh-to-bridge transport/upload failure while keeping packets in mesh buffers completely intact.
- **`MALFORMED_SYNC_MESSAGE`**: Injects invalid schema/control sync messages.
- **`CORRUPTED_PACKET_PAYLOAD`**: Corrupts ciphertext bytes to verify decryption/integrity rejection.
- **`TRANSIENT_DATABASE_FAILURE`**: Injects transient exceptions (`OptimisticLockException`) to test retry backoff.
- **`STALE_RESPONSE`**: Drops post-commit HTTP responses to verify fast-path recovery without double debiting.
- **`DUPLICATE_REQUEST`**: Simultaneous concurrent ingress of identical packet hashes.
- **`CRASH_AND_RESTART`**: Volatile node buffer wipe (`clear()`) followed by full anti-entropy reconstruction.

### 3. Architecture & Narrow Interception
- **`FaultRule`**: Immutable record defining target criteria, occurrence limits, and message classes.
- **`FaultInjector`**: Central deterministic engine maintaining mutable atomic activation counters, rule matching, and metrics recording. Zero overhead and transparent passthrough when disabled.
- **`FaultInterceptor`**: Narrow adapter interface wired into `MeshSimulatorService`, `AntiEntropyService`, `SettlementService`, and `BridgeIngestionService`.
- **`ReliabilityMetrics`**: Thread-safe in-memory counters tracking injections, drops, duplicates, retries, and invariant checks.

### 4. Machine-Checkable Invariants (I1–I12)
- **I1 (Packet Identity)**: $\text{packetHash} \equiv \text{SHA-256}(\text{ciphertext})$.
- **I2 (Transport Deduplication)**: Device stores at most 1 copy of any packet hash.
- **I3 (Settlement Idempotency)**: At most 1 committed settlement per packet hash.
- **I4 (Funds Conservation)**: $\sum \text{liquidBalance} + \sum \text{offlineLockedBalance} \equiv \text{InitialTotalSystemFunds}$.
- **I5 (Non-Negative Escrow)**: Offline wallet remaining escrow $\ge 0$.
- **I6 (Observable Conflict)**: Conflicting counter collisions permanently recorded as `CONFLICTING` and wallet frozen as `LOCKED_DISPUTED`.
- **I7 (Connected Component Convergence)**: Reachable nodes achieve identical `stateDigest` after anti-entropy.
- **I8 (TTL Independence)**: Anti-entropy repairs missing packets regardless of TTL expiration.
- **I9 (Transient Recoverability)**: Transient DB failures release in-flight locks to allow retries.
- **I10 (Permanent Terminality)**: Validation failures terminate without retry loops.
- **I11 (Crash Non-Mutation)**: Node crash/restart does not modify backend ledger.
- **I12 (Cryptographic Barrier)**: Invalid/corrupted signatures are unconditionally rejected.

### 5. Automated Tests & Results
Implemented in `DistributedReliabilityTest.java` (25 tests):
- **Group 1: Isolated Network Faults (7 tests)**:
  1. `testPacketDropRecoveredBySubsequentAntiEntropy` — PASS
  2. `testPacketDuplicationSuppressedByAuthoritativeHash` — PASS
  3. `testReorderedPacketDeliveryObservedAsSequenceGap` — PASS
  4. `testDelayedPacketArrivalAfterAntiEntropyIsDroppedAsDuplicate` — PASS
  5. `testPeerUnavailableTriggersAlternatePeerFallback` — PASS
  6. `testNodeRestartRecoversBufferWithoutCorruptingBackend` — PASS
  7. `testRepeatedPartitionHealCyclesAchieveEventualConvergence` — PASS
- **Group 2: Isolated Bridge & Backend Faults (6 tests)**:
  8. `testBridgeUnavailableKeepsMeshBuffersIntact` — PASS
  9. `testDuplicateBridgeUploadIdempotentlyDeduplicated` — PASS
  10. `testLostHttpResponseRecoversCommittedSettlement` — PASS
  11. `testTransientOptimisticLockExceptionSucceedsOnRetry` — PASS
  12. `testExhaustedRetriesThrowsTransientExceptionAndReleasesLock` — PASS
  13. `testPermanentValidationFailureNeverRetried` — PASS
- **Group 3: Compound & Combination Faults (7 tests)**:
  14. `testCompoundDropAndAntiEntropy` — PASS
  15. `testCompoundDelayAndReorderOfflineWalletSequence` — PASS
  16. `testCompoundDuplicateAndLostResponse` — PASS
  17. `testCompoundPartitionAndConcurrentPayments` — PASS
  18. `testCompoundPartitionAndBridgeUnavailable` — PASS
  19. `testCompoundNodeRestartAndAntiEntropy` — PASS
  20. `testCompoundDuplicateRequestAndOptimisticLockContention` — PASS
- **Group 4: Invariant & Property Tests (5 tests)**:
  21. `testPropertyConservationOfTotalFunds` — PASS
  22. `testPropertyCommutativeStateDigest` — PASS
  23. `testPropertyOfflineEscrowCannotBecomeNegative` — PASS
  24. `testPropertyConflictingCounterAlwaysObservable` — PASS
  25. `testPropertyZeroInvariantViolationsUnderAdverseConditions` — PASS

**Total Project Test Suite**: **96 tests run, 0 failures, 0 errors, 0 skipped.**

### Status

**COMPLETED**

---

# PHASE 6 — Real-Time React Distributed Payment Dashboard

## Objective

Deliver a production-style React dashboard for observing and interacting with the Spring Boot virtual mesh simulator, acting strictly as a visualization and control layer without duplicating domain or financial logic.

### 1. Architectural Principles
- **Visualization & Control Layer Only**: Zero business logic, balance computations, escrow tracking, sequence counter validations, hash calculations, or invariant audits executed on the client.
- **Adaptive Polling**: 2000 ms active tab, 10000 ms background/hidden tab, immediate mutation invalidation refetch. Failures exceeding ~5000 ms trigger an explicit `STALE DATA` warning and disable mutation controls. Persistent connection warning displayed on backend outage.
- **Strict Boundary Integrity**: No WebSockets or SSE; no PostgreSQL, Redis, Android, BLE, Prometheus, or distributed consensus. Legacy `/api/transactions` remains untouched.
- **Authoritative Server Invariants**: Invariants I1–I12 evaluated dynamically by `InvariantAuditService` on the server and consumed read-only by the dashboard.

### 2. Implemented Stack & Directory Structure
- **Frontend Core**: React 18, TypeScript (strict mode), Vite 5, Tailwind CSS, Lucide React icons.
- **Frontend Architecture**:
  ```text
  frontend/src/
    layouts/     AppLayout, Header, Sidebar
    pages/       OverviewPage, MeshPage, WalletsPage, TransactionsPage, ReliabilityPage, FaultInjectionPage
    components/  common/ (Badge, Button, Card, Modal, StatCard)
                 mesh/ (TopologyCanvas, DeviceNode, MeshLink, NodeDetailsDrawer)
                 wallets/ (WalletTable, AllocateModal)
                 transactions/ (TransactionTable, TxReceiptModal)
                 reliability/ (InvariantCard, MetricGauge)
                 faults/ (FaultRuleTable, InjectFaultModal, FaultPresetBar)
    hooks/       usePolling
    services/    api (typed backend REST client)
    types/       strict TypeScript models
    utils/       formatters, constants, topologyLayout
  ```

### 3. Backend DTO & API Surface
- **DTOs** (`com.demo.upimesh.dto`): `DashboardOverviewDto`, `MeshSummaryDto`, `DeviceDetailDto`, `WalletSummaryDto`, `PaginatedTransactionsDto`, `ReliabilityReportDto`, `FaultRuleRequest`.
- **Endpoints** (`DashboardApiController`):
  - `GET /api/dashboard/overview` — Lightweight aggregate metrics only.
  - `GET /api/dashboard/mesh` — Lightweight mesh summary with device list & severed links.
  - `GET /api/dashboard/mesh/devices/{deviceId}` — On-demand deep node inspection (16 bucket checksums, peer sync tables, full packet hashes).
  - `GET /api/dashboard/wallets` — Authoritative escrow & liquid balances.
  - `GET /api/dashboard/transactions` — Paginated and filtered transaction search.
  - `GET /api/dashboard/reliability` — Server-evaluated I1–I12 invariants and `ReliabilityMetrics`.
  - `GET /api/faults/rules`, `POST /api/faults/rule`, `DELETE /api/faults/rule/{faultId}`, `POST /api/faults/reset`, `POST /api/faults/toggle` — Fault injection management with strict backend validation.
- **CORS Configuration** (`WebCorsConfig`): Allows `http://localhost:5173` for `GET`, `POST`, `DELETE`, `OPTIONS` on `/api/**`.

### 4. Automated Verification & Testing
- **Frontend Test Suite** (17 tests across 6 suites in `frontend/src/test/`):
  - `usePolling.test.ts` (3 tests): Active cadence (2s), hidden tab backoff (10s), immediate mutation refetch.
  - `TopologyCanvas.test.tsx` (4 tests): Actual device list rendering, fallback dynamic layout, link partition styling, node detail fetch.
  - `TransactionTable.test.tsx` (3 tests): Pagination controls, status filtering, receipt inspection modal.
  - `FaultRuleTable.test.tsx` (3 tests): Active rules list, individual rule deletion, empty state.
  - `ReliabilityPage.test.tsx` (1 test): Authoritative I1–I12 invariant badges and metrics gauges.
  - `BackendOutage.test.tsx` (3 tests): Stale data banner after 5s outage, mutation controls disabled when stale, persistent disconnection alert.
- **Backend Test Suite**: 103 tests passing (96 Phase 1–5 baseline + 7 new dashboard controller integration tests), 0 failures, 0 errors.

### Status

**COMPLETED**

---

# PHASE 7 — Production Storage & Infrastructure

## Objective

Replace prototype-only infrastructure.

### Database

```text
H2
 ↓
PostgreSQL
```

### Idempotency

```text
ConcurrentHashMap
 ↓
Redis
```

### Containerization

```text
Docker
Docker Compose
```

Services:

```text
Spring Boot
PostgreSQL
Redis
React
Prometheus
Grafana
```

### Status

**NOT STARTED**

---

# PHASE 8 — Observability

## Objective

Make distributed behavior measurable.

### Metrics

Track:

```text
payments_total
payments_settled
payments_rejected
duplicate_packets
replay_attempts
invalid_signatures
gossip_messages
average_hops
packet_loss
settlement_latency
conflicts_detected
reconciliation_duration
```

### Stack

```text
Spring Boot Actuator
Prometheus
Grafana
OpenTelemetry
```

### Status

**NOT STARTED**

---

# PHASE 9 — Real Device / Android BLE Prototype

## Objective

Move from simulated devices to real devices.

### Android

```text
Kotlin
Android
BLE
Local encrypted storage
```

Each phone becomes:

```text
Identity
Wallet
Local Ledger
Transaction Queue
BLE Node
Gossip Participant
```

### Example

```text
Phone A
   │
   │ BLE
   ▼
Phone B
   │
   │ BLE
   ▼
Phone C
   │
   │ Internet
   ▼
Bridge / Backend
```

### Status

**NOT STARTED**

---

# 9. Security Architecture

The final transaction should conceptually contain:

```text
Transaction
│
├── sender
├── receiver
├── amount
├── nonce
├── timestamp
├── transactionId
├── signature
└── encrypted payload
```

Security layers:

```text
                 TRANSACTION
                      │
          ┌───────────┼────────────┐
          ▼           ▼            ▼
       Encrypt      Sign          Hash
          │           │            │
   Confidentiality Authorization Identity
                                   │
                                   ▼
                              Idempotency
                                   │
                                   ▼
                               Settlement
```

---

# 10. Distributed Systems Concepts We Must Demonstrate

The project should provide practical implementation and documentation for:

* Network partitions
* Eventual consistency
* At-least-once delivery
* Exactly-once effect
* Idempotency
* Gossip protocols
* Anti-entropy
* Merkle trees
* Vector clocks
* Causal ordering
* Conflict resolution
* Distributed state reconciliation
* Optimistic concurrency
* Retry/backoff
* Fault tolerance
* Failure detection
* Double-spending
* Offline authorization

---

# 11. Cryptography Concepts We Must Demonstrate

* AES-256-GCM
* RSA-OAEP
* Ed25519
* SHA-256
* Nonces
* Authentication tags
* Digital signatures
* Key management
* Replay protection
* Canonical serialization
* Key rotation
* Secure randomness

---

# 12. Testing Strategy

Every major feature must have tests.

Testing layers:

```text
Unit Tests
     ↓
Integration Tests
     ↓
Concurrency Tests
     ↓
Security Tests
     ↓
Distributed Simulation
     ↓
Fault Injection
     ↓
Load Testing
```

No phase is considered complete simply because the application starts.

---

# 13. Agent Rules

Antigravity and other coding agents must follow these rules.

## Rule 1

Read `PLAN.md` before beginning work.

## Rule 2

Determine the current phase from `PLAN.md`.

## Rule 3

Never implement future-phase functionality unless explicitly requested.

## Rule 4

Do not rewrite working components without justification.

## Rule 5

Preserve existing tests.

## Rule 6

Add tests for every new security-sensitive behavior.

## Rule 7

Never invent security guarantees.

## Rule 8

Never store secrets or private keys in logs.

## Rule 9

Do not introduce dependencies without explaining why.

## Rule 10

Do not modify unrelated files.

## Rule 11

Run tests after implementation.

## Rule 12

Report exactly what changed.

## Rule 13

Do not automatically commit or push unless explicitly instructed.

## Rule 14

Do not merge branches automatically.

## Rule 15

If a design decision has security or distributed-consistency implications, stop and explain the trade-off before implementation.

---

# 14. Definition of Done

A phase is complete only when:

* Implementation is complete
* Tests are passing
* New edge cases are tested
* Documentation is updated
* Security implications are documented
* Existing functionality still works
* No unrelated changes exist
* `git diff` has been reviewed
* The feature branch is ready for Pull Request

---

# 15. Current Progress

| Phase | Component              | Status      |
| ----- | ---------------------- | ----------- |
| 0     | Repository analysis    | DONE        |
| 1     | Cryptographic identity | COMPLETED   |
| 2     | Reliable idempotency   | NOT STARTED |
| 3     | Offline wallet         | NOT STARTED |
| 4     | Advanced gossip        | NOT STARTED |
| 5     | Fault injection        | NOT STARTED |
| 6     | React frontend         | NOT STARTED |
| 7     | PostgreSQL + Redis     | NOT STARTED |
| 8     | Observability          | NOT STARTED |
| 9     | Android + BLE          | NOT STARTED |

---

# 16. Current Immediate Task

The next task is:

> **Phase 2 — Reliable Idempotency & Transaction Processing**

Before implementation:

1. Read the existing crypto architecture.
2. Read Account and PaymentInstruction models.
3. Read BridgeIngestionService.
4. Read SettlementService.
5. Read all existing tests.
6. Propose the exact implementation design.
7. Identify files that will change.
8. Identify schema/database implications.
9. Identify backward compatibility implications.
10. Wait for approval before implementing if the requested workflow requires design review.

---

# 17. Long-Term Project Goal

The final project should demonstrate:

```text
             OFFLINE PAYMENT SYSTEM

                    SECURITY
                       │
          ┌────────────┼────────────┐
          │            │            │
     Encryption    Signatures    Replay
          │            │          Protection
          └────────────┼────────────┘
                       │
                DISTRIBUTED SYSTEM
                       │
       ┌───────────────┼────────────────┐
       │               │                │
     Gossip       Anti-Entropy     Reconciliation
       │               │                │
       └───────────────┼────────────────┘
                       │
                  CONSISTENCY
                       │
              ┌────────┼────────┐
              │        │        │
          Idempotency  Causal   Conflict
                      Ordering  Resolution
              │
              ▼
          SETTLEMENT
              │
              ▼
        FAULT TOLERANCE
              │
              ▼
        OBSERVABILITY
              │
              ▼
       REAL-TIME FRONTEND
              │
              ▼
        REAL DEVICE / BLE
```

The ultimate objective is not merely to demonstrate a payment UI.

The objective is to demonstrate how a secure distributed system can maintain trustworthy transaction processing under intermittent connectivity, message duplication, network partitions, concurrency, and delayed synchronization.

---

# 18. Phase 7 — PostgreSQL + Redis Infrastructure (COMPLETED)

### 18.1 Architectural Principle
**"PostgreSQL is the authoritative financial store. Redis is non-authoritative coordination/cache."**

### 18.2 Schema & Persistence Architecture
* **Flyway Migrations:** Deterministic SQL migrations (`V1__initial_schema.sql`) managing production schema lifecycle. `ddl-auto=create/create-drop` is strictly prohibited in production.
* **Authoritative Tables:**
  * `accounts`: Stores liquid funds and `offline_locked_balance` with non-negative constraints (`NUMERIC(19, 2)`), registered Ed25519 public keys, and optimistic locking (`version`).
  * `offline_wallets`: Tracks escrow allocations, cumulative settled funds, remaining escrow, monotonic sequence counters, expiry timestamps, and conflict states (`ACTIVE`, `LOCKED_DISPUTED`, etc.).
  * `transactions`: Permanent ledger record enforcing `UNIQUE(packet_hash)`, sender/receiver foreign keys, and cryptographic audit signatures.
* **Database Constraints:** Core financial correctness is enforced at the database level:
  * `UNIQUE(packet_hash)`
  * `CHECK (balance >= 0)` and `CHECK (offline_locked_balance >= 0)`
  * `CHECK (settled_amount + remaining_amount <= allocated_amount)`
  * Foreign key referential integrity with `ON DELETE SET NULL` on self-referencing winning transaction pointers.

### 18.3 Distributed Coordination & Graceful Degradation
* **Redis Lock Namespace:** `upi:lock:<packetHash>` with explicit 60-second in-flight TTL and 24-hour completion TTL.
* **Non-Authoritative Resilience:** If Redis is down, in-flight acquisition catches connection failures, increments `redisFallbackTotal`, and falls back to local concurrency gates. The transaction continues to execute safely against the PostgreSQL `UNIQUE(packet_hash)` barrier without duplicate debits or financial corruption.

### 18.4 Dashboard Cache-Aside & Invalidation
* **Cache Keys:** `upi:cache:dashboard:overview`, `upi:cache:dashboard:mesh`, `upi:cache:dashboard:reliability` with 10-second TTL.
* **Mutation-Driven Invalidation:** Eviction occurs immediately upon:
  * Transaction settlement / rejection / conflict
  * Offline wallet allocation / reconciliation
  * Mesh topology partition, heal, flush, or sync
  * Fault rule addition, deletion, toggle, or reset

### 18.5 Verified Durability & Testing
* **Restart Durability:** Proven via `RestartDurabilityIntegrationTest` across Spring ApplicationContext destruction and recreation.
* **Redis Failure Resilience:** Proven via `RedisFailureResilienceTest` during simulated Redis outages.
* **Test Suite:** 111 backend tests (0 failures, 0 errors), 17 frontend tests (0 failures).

---

# 19. Phase 8 — Observability, Metrics & Production Diagnostics (COMPLETED)

### 19.1 Core Observability Principles
1. **Financial Non-Authoritative Invariant:** All gauges and metrics (including `upi.wallets.escrow.total.allocated`) are strictly observational telemetry. They must **never** be treated as authoritative financial state. Exact financial truth resides solely in PostgreSQL.
2. **Strict Bounded Cardinality:** Label values are strictly bounded enums and fixed strings (`status`, `reason`, `fault_type`, `device_role`, `result`). High-cardinality values (`packetHash`, `transactionId`, `walletId`, `ownerVpa`, `requestId`, `nonce`) are strictly prohibited in metric tags to prevent TSDB memory leaks.
3. **Sensitive Data Protection:** Actuator endpoint detail is restricted (`management.endpoint.health.show-details=when_authorized`). No private keys, database passwords, or decrypted payment payloads are ever emitted in logs or metrics.
4. **Read-Only Telemetry:** Observability code does not alter business logic, state transitions, or transaction outcomes.

### 19.2 Metrics Catalogue

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `upi.transactions.attempted` | Counter | `mode=online\|offline` | Ingestion attempts received at bridge |
| `upi.transactions.settled` | Counter | `mode=online\|offline` | Successful settlements committed to DB |
| `upi.transactions.duplicate` | Counter | `stage=idempotency_claim\|db_barrier` | Duplicate packets dropped |
| `upi.transactions.rejected` | Counter | `reason=signature_invalid\|expired\|...` | Validation rejections (bounded reasons) |
| `upi.transactions.conflicting` | Counter | `reason=counter_reuse_detected` | Double-spend attempts detected |
| `upi.transactions.pending.gap` | Counter | - | Out-of-order sequence counter gap events |
| `upi.transactions.retries` | Counter | `outcome=success\|exhausted` | Optimistic lock retries in settlement |
| `upi.settlement.latency` | Timer | `mode=online\|offline` | Latency distribution of settlement transactions |
| `upi.wallets.allocated` | Counter | - | Offline wallet escrow allocations |
| `upi.wallets.reconciled` | Counter | - | Completed wallet reconciliations |
| `upi.wallets.disputed` | Counter | - | Wallets transitioned to `LOCKED_DISPUTED` |
| `upi.wallets.audit.required` | Counter | - | Wallets flagged for manual audit |
| `upi.wallets.expired` | Counter | - | Expired wallet sync events |
| `upi.wallets.escrow.total.allocated` | Gauge | - | Observational escrow total (non-authoritative) |
| `upi.mesh.packets.received` | Counter | `role=node\|bridge` | Mesh packets received across simulated nodes |
| `upi.mesh.packets.forwarded` | Counter | - | Packets hopped to peers |
| `upi.mesh.gossip.rounds` | Counter | - | Gossip sync rounds executed |
| `upi.mesh.sync.operations` | Counter | `result=in_sync\|diff_resolved` | Anti-entropy digest comparison outcomes |
| `upi.mesh.partitions` | Counter | `action=partition\|heal` | Network topology partition/heal events |
| `upi.mesh.bridge.flushes` | Counter | - | Bridge upload executions |
| `upi.mesh.active.nodes` | Gauge | - | Active nodes count in mesh |
| `upi.mesh.buffered.packets` | Gauge | - | Packets in flight within mesh nodes |
| `upi.fault.injections` | Counter | `fault_type=NETWORK_PARTITION\|...` | Fault injection attempts |
| `upi.fault.recoveries` | Counter | `fault_type=NETWORK_PARTITION\|...` | Automated recoveries from faults |
| `upi.fault.invariant.violations` | Counter | `invariant=I1_NO_DOUBLE_SPEND\|...` | System invariant audit violations |
| `upi.infra.redis.ops` | Counter | `op=lock_acquire\|lock_complete\|cache_get\|...` | Redis operations executed |
| `upi.infra.redis.fallbacks` | Counter | `op=lock_acquire\|cache_get\|...` | Fallbacks triggered due to Redis downtime |
| `upi.infra.cache.hits` | Counter | `cache=dashboard_overview\|...` | Dashboard cache hits |
| `upi.infra.cache.misses` | Counter | `cache=dashboard_overview\|...` | Dashboard cache misses |
| `upi.infra.db.retries` | Counter | - | Database transient optimistic lock retries |

### 19.3 Production Diagnostics & Health Model
* **Correlation IDs (`X-Request-ID`):** Automatically propagated via `CorrelationIdFilter` into SLF4J MDC `[req:<id>]`. Returned in all HTTP response headers for end-to-end tracing.
* **Custom Health Indicator (`UpiSystemHealthIndicator`):**
  * `UP` (HTTP 200): PostgreSQL and Redis both operational.
  * `DEGRADED` (HTTP 200): PostgreSQL operational, Redis unavailable (non-authoritative fallback active).
  * `DOWN` (HTTP 503): PostgreSQL unreachable (authoritative financial store down).

### 19.4 Operational Alerting Rules (`alert_rules.yml`)
1. `PostgresUnavailable` (Critical): Backend database down for >1m.
2. `RedisDegraded` (Warning): Redis cache/lock unavailable; fallback active for >2m.
3. `HighSettlementLatency` (Warning): P99 settlement latency > 500ms.
4. `HighTransactionRejectionRate` (Warning): Rejection rate > 15% of total attempts.
5. `ConflictingTransactionsDetected` (Critical): Counter re-use or double-spend detected.
6. `InvariantViolationDetected` (Critical): Core financial or consensus invariant violated.
7. `MeshConvergenceFailure` (Warning): Gossip divergence detected during anti-entropy sync.
8. `ExcessiveDbRetries` (Warning): Optimistic lock contention rate > 5 retries/sec.

### 19.5 Grafana Production Dashboards
* `01-system-overview.json`: System health, total throughput, active faults, and invariant status.
* `02-transactions.json`: Attempted, settled, duplicate, rejected, and conflict rates with P50/P95/P99 latency.
* `03-wallets-escrow.json`: Wallet allocations, reconciliations, disputed counts, and observational escrow gauge.
* `04-mesh-convergence.json`: Packet propagation, hop distribution, partition status, and anti-entropy sync.
* `05-reliability-faults.json`: Active fault injections, automated recovery rate, and zero-violation monitor.
* `06-infrastructure.json`: PostgreSQL connections/retries, Redis latency/fallbacks, and cache hit ratio.

### 19.6 Verification Results
* **Backend Test Suite:** 127 automated tests across 16 test classes — 100% passing (0 failures, 0 errors, 0 skipped).
* **Frontend Test Suite:** 17 Vitest unit and integration tests — 100% passing. Production build succeeds cleanly.
* **Docker Compose Validation:** Validated multi-container composition with PostgreSQL 16, Redis 7, Prometheus 2.51, and Grafana 10.4.

---

# 20. Phase 9.1 — Cross-Language Cryptographic Compatibility (COMPLETED)

### 20.1 Purpose & Scope
Phase 9.1 implements deterministic cross-language cryptographic interoperability between the Java 17 Spring Boot backend and the Kotlin Android peripheral library (`:core-crypto`).

### 20.2 Strict Security Notice: Test-Only Deterministic Fixtures
> [!IMPORTANT]
> **TEST-ONLY CRYPTOGRAPHIC FIXTURES**:
> All keys, signatures, and envelopes defined in `upi_crypto_test_vectors_v1.json` are generated from deterministic PRNG seeds solely for automated cross-language verification.
> 1. **Location Isolation:** Test vector files exist **ONLY** under `src/test/resources/` and `android/core-crypto/src/test/resources/`.
> 2. **No Production Linkage:** Production source under `src/main/` and `android/**/src/main/` never imports, references, loads, or packages these test keys or vectors.
> 3. **Non-Production Keys:** These keys must **never** be used in production environments. Production deployments require hardware-backed Keystore/StrongBox keys and KMS/HSM server keys.

### 20.3 Interoperability Guarantee & Test Vectors
* **Canonicalization:** Byte-for-byte UTF-8 string identity for `v1`, `v3_tx`, `v1_cert`, and `v1_receipt`.
* **Ed25519 Interoperability:** Java signs $\to$ Kotlin verifies; Kotlin signs $\to$ Java verifies (exact 64-byte RFC 8032 signatures).
* **Hybrid Envelope Interoperability:** Android-generated envelopes (RSA-2048-OAEP SHA-256/MGF1-SHA-256 + AES-256-GCM 12-byte IV + 128-bit tag) unpack and decrypt cleanly in Java `HybridCryptoService`.
* **Content Identity:** `packetHash` SHA-256 generates identical 64-character lowercase hexadecimal digests.

### 20.4 Verification Suite
* **Backend Java Tests:** 139 tests passing (129 Phase 1–8 tests + 10 cross-language compatibility tests in `CrossLanguageCryptoCompatibilityTest`).
* **Android Kotlin Tests:** 15 unit tests passing in `core-crypto` (covering canonicalization, Ed25519, cert/receipt verification, hybrid encryption, and packet hashing).

---

# 21. Phase 9.2 — Android Room Persistence + Offline Wallet Engine (COMPLETED)

### 21.1 Architectural Principle
> [!IMPORTANT]
> **LOCAL EXECUTION CACHE VS AUTHORITATIVE LEDGER**:
> Android local state is a durable execution/replication cache. PostgreSQL remains the authoritative financial ledger.
> The Android client never claims authoritative balance, final settlement, global idempotency, or dispute arbitration.

### 21.2 Currency Unit Standard
All monetary values in `:core-database` entities, DAOs, and engines are stored and manipulated strictly as **`Long` integer paisa** (₹1.00 = `100L`, ₹1,500.00 = `150000L`). Floating-point types (`Float`, `Double`) are prohibited for financial calculations.

### 21.3 Local Settlement Semantics & Escrow Terminology
Local payment creation is a pending offline intent; authoritative settlement occurs only at PostgreSQL.
* `allocatedAmountPaisa`: Total offline spending allowance granted by the backend.
* `remainingAmountPaisa`: Spendable local allowance (`allocatedAmountPaisa - localSpentAmountPaisa`). Decremented upon local spend.
* `localSpentAmountPaisa`: Cumulative offline spends committed locally on this device. Incremented upon local spend.
* `settledAmountPaisa`: Authoritative settled amount. **NEVER** incremented by local offline spend. It is updated **ONLY** when a cryptographically verified backend `SettlementReceipt` arrives.

### 21.4 Key Storage Architecture & Memory Hygiene
* **Master Key:** Android Keystore AES-256-GCM under stable alias `upi_mesh_master_key`.
* **Encrypted at Rest:** Device Ed25519 private keys are encrypted at rest with random 12-byte IV and 128-bit authentication tag.
* **Non-Exportable:** Master key cannot be exported from hardware security module / KeyStore.
* **Memory Hygiene:** Best-effort RAM zeroization is applied to sensitive byte arrays (`Arrays.fill(data, 0.toByte())`) upon completion of cryptographic operations. (Documented as best-effort memory hygiene due to runtime garbage collection).

### 21.5 Room Entity Schema & Indexes
1. `DeviceIdentity` (`device_identities`):
   - PK: `deviceId: String`
   - Indexed: `owner_vpa`
   - Fields: `publicKey`, `encryptedPrivateKey`, `encryptionIv`, `enrollmentState`, `createdAt`, `updatedAt`
2. `OfflineWallet` (`offline_wallets`):
   - PK: `walletId: String`
   - Indexed: `owner_vpa`
   - Fields: `ownerPublicKey`, `allocatedAmountPaisa`, `localSpentAmountPaisa`, `settledAmountPaisa`, `remainingAmountPaisa`, `sequenceCounter`, `walletEpoch`, `validFrom`, `validUntil`, `certificateJson`, `status`, `updatedAt`
   - States: `ACTIVE`, `EXPIRED`, `LOCKED_DISPUTED`, `AUDIT_REQUIRED`, `RECONCILED_CLOSED`, `PENDING_RECONCILE`
3. `OutboundPayment` (`outbound_payments`):
   - PK: `paymentId: String` (UUID)
   - Indexed: `wallet_id`, unique composite `(wallet_id, sequence_counter)`, `packet_hash`
   - Fields: `amountPaisa`, `cumulativeAmountPaisa`, `receiverVpa`, `nonce`, `packetHash`, `ciphertext`, `state`, `createdAt`, `updatedAt`, `retryCount`
   - States: `CREATED`, `ENCRYPTED`, `READY_FOR_TRANSPORT`, `PENDING_BRIDGE`, `SETTLEMENT_CONFIRMED`, `REJECTED`, `CONFLICTING`, `EXPIRED`
4. `ReceivedPacket` (`received_packets`):
   - PK: `packetHash: String`
   - Fields: `packetId`, `ciphertext`, `ttl`, `hopCount`, `receivedAt`, `uploadedToBridge`, `status`, `updatedAt`
   - Invariant: Local duplicate rejection optimization.
5. `PacketFragment` (`packet_fragments`):
   - Composite PK: `(packetHash, chunkIndex)`
   - Indexed: `packet_hash`
   - Fields: `totalChunks`, `data: ByteArray`, `receivedAt`
6. `SettlementReceipt` (`settlement_receipts`):
   - PK: `transactionId: Long`
   - Indexed: `packet_hash`
   - Fields: `counter`, `status`, `settledAt`, `serverSignature`
   - Rule: Signature verified before storage.

### 21.6 Atomic Payment Creation Semantics
A single atomic database transaction performs:
1. Validate wallet (owner matches, active status, unexpired).
2. Validate counter (`nextCounter == sequenceCounter + 1`, no rollback).
3. Validate remaining allowance (`remainingAmountPaisa >= amountPaisa`).
4. Update wallet balances (`remainingAmountPaisa -= amountPaisa`, `localSpentAmountPaisa += amountPaisa`, `sequenceCounter++`).
5. Insert `OutboundPayment` with immutable intent data in `CREATED` state.
6. Generate canonical `v3_tx`, sign with decrypted device Ed25519 key, hybrid-encrypt payload with server RSA key, compute `packetHash`.
7. Update `OutboundPayment` with `ciphertext`, `packetHash`, and state `READY_FOR_TRANSPORT`.
8. Commit transaction. Only committed payments are eligible for subsequent transport.

### 21.7 Deterministic Restart & Crash Recovery
The `StartupRecoveryManager`:
* `CREATED`: Resumes preparation from persisted immutable intent without altering `nonce`, `sequenceCounter`, `amountPaisa`, or `receiverVpa`.
* `ENCRYPTED`: Verifies `packetHash == SHA-256(ciphertext)` and transitions to `READY_FOR_TRANSPORT`.
* `READY_FOR_TRANSPORT`, `PENDING_BRIDGE`, `SETTLEMENT_CONFIRMED`: Preserved exactly.
* `REJECTED`, `CONFLICTING`, `EXPIRED`: Preserved as terminal states.
* **No Regeneration:** No recovery path ever creates a second logical payment or duplicates sequence numbers.
* **Fragment Hygiene:** Purges stale packet fragments older than TTL window (24 hours).

### 21.8 Verification Results
* **Android Test Suite:** 44 tests passing (15 in `:core-crypto` + 29 in `:core-database`).
  - `RoomDaoAndPersistenceTest` (5 tests): CRUD, paisa enforcement, deduplication, composite keys, process restart file reload.
  - `DatabaseMigrationTest` (3 tests): v1 schema creation, v1 to v2 migration, non-destructive migration guarantee.
  - `KeyStoreManagerTest` (3 tests): AES-256-GCM roundtrip, tamper detection, best-effort zeroization.
  - `OfflineWalletEngineAndAtomicityTest` (7 tests): Atomic commit, Test A (settledAmount untouched), Test B (remaining allowance decreased), sequence rollback rejection, escrow exhaustion, expired wallet rejection, self-spend/unauthorized spender rejection.
  - `StartupRecoveryAndDeterminismTest` (6 tests): Test C (exact intent preserved on restart), Test D (ciphertext/hash preserved), Test E (no duplicate logical payments), crash before transport survival, stale fragment purge, startup expired wallet detection.
  - `SettlementReceiptAndAuthorityTest` (3 tests): Test F (receipt is only transition updating settledAmount), forged receipt rejection, No False Authority guarantees.
  - `ObservabilityMetricsTest` (2 tests): Counters and bounded labels.
* **Backend Java Tests:** 139 tests passing (100% green).

---

# 22. Important Disclaimer

This is an engineering/research prototype inspired by offline digital payment concepts.

It is NOT:

* An official UPI implementation
* A replacement for NPCI infrastructure
* A production banking system
* A guarantee of real-world offline monetary settlement

All security and consistency claims must be limited to what is actually implemented and experimentally verified.


