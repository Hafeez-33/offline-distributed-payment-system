# Offline Distributed Payment System

## Long-Term Engineering Plan

> **Status:** Active Development
> **Current Phase:** Phase 0 — Repository Understanding / Baseline
> **Primary Backend:** Java 17 + Spring Boot
> **Target Frontend:** React + TypeScript
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

Transform the basic simulated gossip into a more realistic synchronization protocol.

### Current

```text
Broadcast packet
      ↓
TTL decrement
      ↓
Forward
```

### Target

```text
Peer Discovery
      ↓
State Digest
      ↓
Compare Knowledge
      ↓
Request Missing Transactions
      ↓
Transfer Missing Data
      ↓
Verify
      ↓
Merge
```

### Build

#### 4.1 Peer state

Each node tracks what transactions it knows.

#### 4.2 Anti-entropy

Nodes periodically exchange summaries.

#### 4.3 Merkle trees

Use Merkle trees to efficiently identify divergent transaction sets.

```text
             Root
            /    \
          H1      H2
         /  \    /  \
       TX1 TX2 TX3 TX4
```

#### 4.4 Vector clocks

Track causal relationships between distributed events.

Example:

```text
Node A: [4,2,1]
Node B: [3,5,1]
Node C: [3,2,7]
```

#### 4.5 Conflict detection

Identify:

* Duplicate transactions
* Concurrent transactions
* Conflicting state
* Double-spend attempts

### Status

**NOT STARTED**

---

# PHASE 5 — Fault Injection & Distributed Testing

## Objective

Prove the system behaves correctly under failure.

### Faults

Simulate:

```text
Packet loss
Packet duplication
Packet delay
Network partition
Node crash
Bridge failure
Concurrent transactions
Replay attack
Tampered packet
Out-of-order delivery
```

### Example

```text
Network:

A ─── B ─── C

Partition:

A       X       B ─── C
```

Then reconnect and verify convergence.

### Dashboard controls

Eventually:

```text
Packet Loss:       20%
Packet Delay:      500ms
Duplicate Rate:    10%
Node Failures:     2

[ RUN EXPERIMENT ]
```

### Metrics

Measure:

* Settlement success
* Settlement latency
* Duplicate rejection
* Conflict detection
* Recovery time
* Gossip convergence
* Packet delivery rate

### Status

**NOT STARTED**

---

# PHASE 6 — Real-Time React Frontend

## Objective

Replace the basic demo dashboard with a professional engineering dashboard.

### Stack

```text
React
TypeScript
Tailwind CSS
WebSocket/SSE
```

### Pages

#### 6.1 System Dashboard

Display:

```text
Online/Offline status
Mesh nodes
Pending transactions
Settled transactions
Rejected transactions
Conflicts
Security events
```

#### 6.2 Mesh Visualization

Show:

```text
Phone A
   │
   ▼
Phone B
   │
   ▼
Phone C
   │
   ▼
Bridge
   │
   ▼
Backend
```

Animate packet propagation.

#### 6.3 Transaction Explorer

Display:

```text
Transaction ID
Sender
Receiver
Amount
Timestamp
Status
Packet hash
Hop count
TTL
Signature status
```

#### 6.4 Security Monitor

Display:

```text
Invalid signatures
Replay attempts
Duplicate packets
Tampered packets
Double-spend conflicts
```

#### 6.5 Event Stream

Example:

```text
21:42:01 PAYMENT_CREATED
21:42:01 ENCRYPTED
21:42:02 NODE_A_RECEIVED
21:42:02 GOSSIP_PROPAGATED
21:42:04 BRIDGE_RECEIVED
21:42:05 SIGNATURE_VERIFIED
21:42:05 IDEMPOTENCY_CLAIMED
21:42:05 SETTLEMENT_COMMITTED
```

### Status

**NOT STARTED**

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

# 18. Important Disclaimer

This is an engineering/research prototype inspired by offline digital payment concepts.

It is NOT:

* An official UPI implementation
* A replacement for NPCI infrastructure
* A production banking system
* A guarantee of real-world offline monetary settlement

All security and consistency claims must be limited to what is actually implemented and experimentally verified.
