# UPI Offline Mesh — Demo

A Spring Boot backend that demonstrates **offline UPI payments routed through a Bluetooth-style mesh network**. You're in a basement with zero connectivity. You send your friend ₹500. Your phone encrypts the payment, broadcasts it to nearby phones, and the packet hops device-to-device until *some* phone walks outside, gets 4G, and silently uploads it to this backend. The backend decrypts, deduplicates, and settles.

This repo is the **server side** of that system, plus a software simulator of the mesh so you can demo the whole flow on a single laptop without any real Bluetooth hardware.

---

## Table of Contents

1. [What this demo proves](#what-this-demo-proves)
2. [How to run it](#how-to-run-it)
3. [The demo flow (step by step)](#the-demo-flow-step-by-step)
4. [Architecture](#architecture)
5. [The three hard problems and how they're solved](#the-three-hard-problems-and-how-theyre-solved)
6. [File-by-file walkthrough](#file-by-file-walkthrough)
7. [API reference](#api-reference)
8. [Tests](#tests)
9. [What's NOT real (and what would change for production)](#whats-not-real-and-what-would-change-for-production)
10. [Honest limitations of the concept](#honest-limitations-of-the-concept)

---

## What this demo proves

The system shows three things working end to end:

1. **A payment can travel from sender to backend through untrusted intermediaries** without any of them being able to read or tamper with it. (Hybrid RSA + AES-GCM encryption.)
2. **Even if the same payment reaches the backend simultaneously through multiple bridge nodes, it settles exactly once.** (Idempotency via atomic compare-and-set on the ciphertext hash.)
3. **A tampered or replayed packet is rejected** before it touches the ledger.

You'll see all three in the dashboard.

---

## How to run it

### Prerequisites

- **JDK 17 or newer** installed and on PATH (or `JAVA_HOME` set). Check with `java -version`.
- That's it. No database, no Redis, no Maven (the wrapper handles it). Just Java.

### Run on Windows

Open a terminal in the project folder and run:

```cmd
mvnw.cmd spring-boot:run
```

The first run downloads Maven (~10 MB) and all dependencies (~80 MB) — give it a couple of minutes. Subsequent runs start in a few seconds.

### Run on Mac/Linux

```bash
./mvnw spring-boot:run
```

### Run Frontend Dashboard (React + TypeScript + Vite)

In a separate terminal, navigate to the `frontend/` directory:

```bash
cd frontend
npm install
npm run dev
```

The frontend dashboard will be available at:

**http://localhost:5173**

It automatically proxies `/api` requests to the backend on `http://localhost:8080`.

### Legacy Dashboard

The legacy backend-rendered Thymeleaf dashboard remains accessible at **http://localhost:8080**.

### Stop the servers

`Ctrl+C` in each terminal.

### Run the tests

#### Backend Tests (127 tests across 16 classes)

```cmd
mvnw.cmd test
```

Runs the complete 127-test automated verification suite:
- **`SignatureServiceTest`** (10 tests): Ed25519 canonicalization, signing, and verification.
- **`CryptographicIdentityTest`** (10 tests): Sender cryptographic authorization & replay prevention.
- **`IdempotencyConcurrencyTest`** (3 tests): 3-bridges concurrent delivery & tamper detection.
- **`ReliableIdempotencyTest`** (13 tests): Optimistic-lock retry, lost-response recovery, and transient failure handling.
- **`OfflineWalletReliabilityTest`** (20 tests): Escrow allocation, sequence gap state machine, fork detection, and receipt validation.
- **`AdvancedGossipSyncTest`** (15 tests): Pairwise anti-entropy, state digests, 16-bucket slicing, and partition healing.
- **`DistributedReliabilityTest`** (25 tests): Phase 5 deterministic fault injection across network, bridge, compound, and property scenarios.
- **`DashboardApiControllerTest`** (7 tests): Phase 6 REST API endpoints, DTO contracts, fault validation, and single-rule removal.
- **`FlywayMigrationTest`** (3 tests): Phase 7 Flyway V1 schema migration, table verification, and unique index constraints.
- **`PostgresPersistenceIntegrationTest`** (Phase 7): PostgreSQL container persistence and `UNIQUE(packet_hash)` DB-level enforcement.
- **`RestartDurabilityIntegrationTest`** (1 test): Phase 7 ledger, escrow, and idempotency barrier survival across ApplicationContext restart.
- **`RedisFailureResilienceTest`** (1 test): Phase 7 non-authoritative Redis outage fallback and duplicate settlement protection.
- **`RedisCoordinationAndCacheTest`** (3 tests): Phase 7 distributed locks, cache hit/miss semantics, and mutation invalidation.
- **`ObservabilityMetricsTest`** (6 tests): Phase 8 Micrometer metric counters, timers, gauges, cardinality guards, and invariant recording.
- **`HealthAndDiagnosticsTest`** (6 tests): Phase 8 Actuator `/actuator/health`, `/actuator/prometheus`, custom UP/DEGRADED/DOWN health indicator, and credential protection.
- **`CorrelationIdAndLoggingTest`** (4 tests): Phase 8 `X-Request-ID` generation, propagation, response header echo, and SLF4J MDC cleanup.

#### Frontend Tests (17 tests across 6 suites)

```bash
cd frontend
npm test -- --run
```

Runs Vitest + React Testing Library + MSW:
- `usePolling.test.ts` (3 tests): Active cadence (2s), hidden tab backoff (10s), immediate mutation refetch.
- `TopologyCanvas.test.tsx` (4 tests): Actual device list rendering, fallback dynamic layout, link partition styling, node detail fetch.
- `TransactionTable.test.tsx` (3 tests): Pagination controls, status filtering, receipt inspection modal.
- `FaultRuleTable.test.tsx` (3 tests): Active rules list, individual rule deletion, empty state.
- `ReliabilityPage.test.tsx` (1 test): Authoritative I1–I12 invariant badges and metrics gauges.
- `BackendOutage.test.tsx` (3 tests): Stale data banner after 5s outage, mutation controls disabled when stale, persistent disconnection alert.

---

## The demo flow (step by step)

The dashboard has four buttons that walk through the full pipeline. The intended sequence:

### Step 1 — Compose a payment

Choose sender, receiver, amount, PIN. Click **"📤 Inject into Mesh"**.

**What actually happens on the backend:**
- The server pretends to be the sender's phone.
- It builds a `PaymentInstruction` with a unique nonce and current timestamp.
- It encrypts that with the server's RSA public key (using hybrid encryption — see below).
- It wraps the ciphertext in a `MeshPacket` with a TTL of 5.
- It hands the packet to `phone-alice`, an offline virtual device.

You'll see `phone-alice` now holds 1 packet.

### Step 2 — Run gossip rounds

Click **"🔄 Run Gossip Round"**. Then click it again.

Each round, every device that holds a packet broadcasts it to every other device within "Bluetooth range" (which, in our simulator, means everyone). TTL decrements per hop.

After 1 round: every device holds the packet. After 2 rounds: still every device — TTL is just lower.

In the real system this would happen organically as people walk past each other in the basement.

### Step 3 — Bridge node walks outside

Click **"📡 Bridges Upload to Backend"**.

`phone-bridge` is the only device with `hasInternet=true`. The dashboard simulates that phone walking outside and getting 4G. It POSTs every packet it holds to `/api/bridge/ingest`.

The backend pipeline runs:
1. Hash the ciphertext (`SHA-256`).
2. Try to claim the hash in the idempotency cache.
3. If claimed: decrypt with the server's RSA private key.
4. Verify freshness (signedAt within 24 hours).
5. Run the debit/credit in a single DB transaction.

Watch the **Account Balances** table — money has moved. Watch the **Transaction Ledger** — a new row appears.

### Step 4 — Demonstrate idempotency (the killer feature)

Reset the mesh. Inject a single packet. Run gossip 2 times. Now **all 5 devices hold the same packet, including multiple bridges in a more complex setup**.

To really see idempotency in action, modify `MeshSimulatorService.java` to seed multiple bridge devices, or just:

1. Click "Inject" once.
2. Click "Gossip" twice.
3. Click "Flush Bridges" — only `phone-bridge` is a bridge in the default seed, so just one upload happens.

To exercise the *concurrent duplicate* case properly, run the test:
```cmd
mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
```

This test creates one packet, fires 3 threads at `BridgeIngestionService.ingest()` simultaneously, and verifies that exactly one settles, two are dropped as duplicates, and the sender is debited exactly once.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER PHONE (offline)                          │
│  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│              │                                                          │
│              ▼ encrypt with server's RSA public key                     │
│   MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │ Bluetooth gossip
                                       ▼
        ┌─────────┐  hop   ┌─────────┐  hop   ┌─────────┐
        │stranger1│ ─────▶ │stranger2│ ─────▶ │ bridge  │ ◀── walks outside
        └─────────┘        └─────────┘        └────┬────┘     gets 4G
                                                   │
                                                   ▼ HTTPS POST
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT BACKEND (this project)                  │
│                                                                         │
│  /api/bridge/ingest                                                     │
│       │                                                                 │
│       ▼                                                                 │
│  [1] hash ciphertext (SHA-256)                                          │
│       │                                                                 │
│       ▼                                                                 │
│  [2] IdempotencyService.claim(hash)  ◀── atomic putIfAbsent (≈ Redis    │
│       │                                  SETNX). Duplicates rejected    │
│       │                                  here, before any work.         │
│       ▼                                                                 │
│  [3] HybridCryptoService.decrypt(ciphertext)                            │
│       │       (RSA-OAEP unwraps AES key, AES-GCM decrypts payload       │
│       │        AND verifies the auth tag — tampering = exception)       │
│       ▼                                                                 │
│  [4] Freshness check: signedAt within last 24h                          │
│       │                                                                 │
│       ▼                                                                 │
│  [5] SettlementService.settle()                                         │
│       @Transactional: debit sender, credit receiver, write ledger       │
│       @Version on Account = optimistic locking (defense in depth)       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## The three hard problems and how they're solved

### Problem 1: Untrusted intermediates & sender authorization

Two distinct cryptographic problems must be solved when payments route through untrusted intermediaries:

1. **Confidentiality & In-Flight Integrity (Transport Envelope)**: Intermediaries must not read or tamper with transaction details.
   - **Solution**: **Hybrid Encryption (RSA-2048-OAEP + AES-256-GCM)**. The sender encrypts the payload using the server's public key. AES-GCM guarantees authenticated encryption; any bit flip causes decryption failure.
2. **Sender Authorization (Cryptographic Identity)**: Encryption alone does *not* prove who created the payment. Since the server's RSA public key is public, anyone could craft a valid ciphertext claiming to be `alice@demo`.
   - **Solution**: **Ed25519 Digital Signatures**. Before encryption, the sender signs a canonical representation of the transaction (`v1|sender=...|receiver=...|amount=...|nonce=...|signedAt=...`) with their Ed25519 private key. Upon decryption, the server resolves the sender's authoritative registered public key from the database and verifies the signature before any settlement occurs.

```text
Encryption (RSA-OAEP + AES-GCM) ──► Confidentiality & Payload Integrity
Ed25519 Digital Signature       ──► Cryptographic Sender Authorization
SHA-256 (Ciphertext Hash)       ──► Packet Identity & Deduplication Gate
Freshness & Nonce               ──► Replay Attack Protection
```

> **Note on Private Key Storage**: In this prototype backend simulator, `DemoService` maintains an in-memory client private key store to simulate mobile phones creating payments. In a production mobile client, private keys must be generated inside hardware-backed storage (Android Keystore / StrongBox / Apple Secure Enclave) where keys are non-exportable and protected by biometric or PIN authentication.

### Problem 2: The duplicate-storm & reliable settlement

Three bridge nodes hold the same packet. They all walk outside at the same instant. They all POST to `/api/bridge/ingest` within milliseconds of each other. If you naively process all three, the sender is debited ₹1500 instead of ₹500. Furthermore, if a transient database locking collision occurs, a naive in-memory claim could permanently drop valid retries.

**Reliability Guarantee:**
The system provides **at-least-once delivery with effectively-once settlement semantics within the authoritative database**. It does NOT claim global exactly-once delivery across untrusted networks, but guarantees that every unique payment instruction has an effectively-once state transition on the financial ledger.

**Solution: Multi-layered deduplication and transaction-safe retry.**

1. **Authoritative Database Barrier (`UNIQUE(packet_hash)`)**:
   The relational database `UNIQUE` constraint on `Transaction.packetHash` is the absolute source of truth. No in-memory cache replaces this authority.

2. **In-Flight Concurrency Gate (`tryAcquire` / `release`)**:
   `IdempotencyService` uses a thread-safe in-memory map to act as an in-flight concurrency gate:
   - `tryAcquire(packetHash)`: Atomically admits the first thread to process the packet and immediately rejects concurrent duplicates.
   - `release(packetHash)`: If transient processing errors occur before a database commit (e.g. database retry exhaustion, validation failures), the in-flight claim is cleanly released, preventing valid retries from being permanently dropped.
   - `markCompleted(packetHash)`: When the transaction successfully commits to the database, the hash is retained in the fast-path cache.

3. **Lost-Response Recovery (Recovery Fast-Path)**:
   Before attempting to acquire an in-flight lock, `BridgeIngestionService` checks `TransactionRepository.findByPacketHash(hash)`. If a packet was already committed (e.g., if a client or bridge lost the HTTP response after settlement and retransmitted), the server immediately returns the original committed transaction result (`SETTLED` or `REJECTED`) without re-executing ledger updates or moving balances twice.

4. **Bounded Optimistic-Lock Retry**:
   Account entities use `@Version` fields for optimistic concurrency control. Under high concurrency on the same account (e.g. concurrent payments from Alice), `SettlementService` performs bounded retries (up to 3 attempts with exponential backoff and jitter: ~25ms, ~50ms). Crucially, **each retry executes in an independent, fresh database transaction** (`PROPAGATION_REQUIRES_NEW`) to guarantee transactional isolation.

5. **Deterministic Failure Classification**:
   - `INVALID` (e.g. `invalid_signature`, `stale_packet`, `unknown_sender`): Permanent rejection, claim released.
   - `REJECTED` (e.g. `insufficient_balance`): Permanent business failure, recorded as a `REJECTED` transaction in the database, completed claim retained.
   - `TRANSIENT_FAILURE` (e.g. `transient_settlement_failure`): Transient failure after retry exhaustion, in-flight claim released, bridge can retry later.

---

### Problem 3: Replay attacks

An attacker who captured a ciphertext weeks ago could replay it whenever convenient.

**Solution: Two layers.**

1. **Inside the encrypted payload**, the sender includes `signedAt` (epoch millis). The server rejects any packet older than 24 hours (or future-dated by >5 minutes). The attacker can't change `signedAt` without breaking the signature and GCM tag.
2. **Inside the encrypted payload**, the sender includes a **nonce** (UUID). Even if Alice legitimately sends Bob ₹100 twice, the nonces differ → canonical strings differ → signatures differ → ciphertexts differ → hashes differ → both settle. But a *replay* of one specific signed packet is byte-identical, so the idempotency cache catches it.

See `BridgeIngestionService.java` for the freshness check.

---

### Problem 4: Offline Spending & Double-Spending Mitigation (Phase 3)

In pure offline mesh environments without server connectivity, an untrusted device could sign multiple conflicting spends exceeding its balance. Phase 3 implements an escrowed offline wallet architecture with bounded exposure, monotonic sequence progression, and server-side fork detection.

#### Non-Negotiable Security Model
- **No absolute prevention claim**: Software-only devices cannot provide absolute physical anti-cloning guarantees. We explicitly do NOT claim: *"Offline double spending is completely prevented."*
- **The actual guarantees**:
  1. **Identical replay** is prevented by Phase 2 authoritative packet hashing and database uniqueness.
  2. **Offline authorized exposure** is strictly bounded by the server-signed escrow allocation (`OfflineWalletCertificate`).
  3. **Conflicting offline spends** are detected during reconciliation via monotonic counter analysis.
  4. **Conflicting wallet histories** are immediately frozen in `LOCKED_DISPUTED` state with auditable dispute records.
  5. **Rollback-resistant protection**: Production grade protection against malicious rollback/cloning requires hardware-backed keys and monotonic state (Android StrongBox / eSE).

#### Escrow Accounting Model
Invariant:
$$\text{Account Total Funds} = \text{Liquid Available Balance} + \text{Offline Locked Balance}$$

- **Allocation**: When allocating ₹X to an offline wallet, ₹X is debited from liquid balance and credited to `offlineLockedBalance`. An `OfflineWallet` entity is created, and a server-signed `OfflineWalletCertificate` is issued.
- **Settlement**: When an offline transaction arrives, ₹amount is subtracted from sender's `offlineLockedBalance` and credited to recipient's liquid balance. The sender's liquid balance is **never debited twice**.
- **Reconciliation/Expiry**: Unused escrow ($\text{allocatedAmount} - \text{settledAmount}$) is automatically returned to the sender's liquid balance upon wallet closure.

#### Server-Signed Offline Wallet Certificate
The server signs an `OfflineWalletCertificate` using its Ed25519 issuer private key covering canonical string `v1_cert|walletId=...|ownerVpa=...|ownerPublicKey=...|allocatedAmount=...|walletEpoch=...|validFrom=...|validUntil=...|initialCounter=...`.
During reconciliation, the backend cryptographically verifies:
1. Issuer signature
2. Certificate validity window
3. Registered public key binding
4. Wallet ownership
5. Wallet epoch
6. Allocation limit

#### Monotonic Sequence State Machine & Fork Detection
Transactions carry an incrementing `sequenceCounter`. During settlement:
- **`counter == lastSettledCounter + 1` (In-Order)**:
  - Debit escrow, credit receiver, advance `lastSettledCounter = counter`.
  - Issue signed `SettlementReceipt`.
  - Cascade-process any pending transactions waiting for sequence gap resolution.
- **`counter > lastSettledCounter + 1` (Sequence Gap)**:
  - Staged in `PENDING_SEQUENCE_GAP` status.
  - Held up to configurable gap window (default 30 min). If window expires, marked `REJECTED_UNRESOLVED_SEQUENCE_GAP` and wallet marked `AUDIT_REQUIRED`.
  - If a conflicting transaction with a different hash appears for the same gap counter, flags `CONFLICTING` (`conflicting_fork_in_sequence_gap`) and freezes the wallet.
- **`counter <= lastSettledCounter` (Duplicate / Fork Analysis)**:
  - If `packetHash == settledTx.packetHash`: Phase 2 duplicate recovery returns committed record.
  - If `packetHash != settledTx.packetHash`: **Conflicting counter fork detected!** Marked `CONFLICTING` with reason `double_spend_counter_collision`, wallet frozen to `LOCKED_DISPUTED`, pending sequence gaps cancelled, and dispute record linked to winning transaction.
  - **Authoritative Winner Policy**: *"Among conflicting transactions that reach the authoritative backend, the first valid transaction to commit is the settlement winner."*

#### Epoch Semantics & Terminal Transfer Policy
- Wallets use strictly increasing epochs ($E_1, E_2, \dots$); at most one ACTIVE epoch exists per wallet. Obsolete epoch transactions are rejected with `obsolete_wallet_epoch`.
- **Terminal Transfer Policy**: Transfers are strictly $\text{Payer} \to \text{Payee} \to \text{Backend}$. Payees cannot re-spend received offline funds offline.

#### Signed Settlement Receipt
Upon successful settlement, the backend generates a `SettlementReceipt` signed by the server's Ed25519 issuer key over canonical representation `v1_receipt|txId=...|hash=...|counter=...|status=...|settledAt=...`, providing cryptographic proof of settlement to the recipient.

---

### Problem 5: Advanced Gossip & Distributed Synchronization (Phase 4)

Pure broadcast gossip suffers from redundant message storms, permanent packet drops when TTL expires, and an inability to reconcile partitioned mesh networks upon reconnection. Phase 4 implements a hybrid synchronization protocol combining rapid epidemic push with deterministic anti-entropy pull.

#### 1. Authoritative Content Identity vs Outer Transport Header
- **`packetHash = SHA-256(ciphertext)`**: The cryptographic content identity used exclusively for deduplication, state digests, prefix bucket checksums, and sync requests.
- **`packetId`**: An unauthenticated transport UUID used only for diagnostic logging and outer hop tracing.

#### 2. Deterministic State Digest & 16-Bucket Prefix Slicing
- **Empty State**: `stateDigest = SHA-256("EMPTY")`.
- **Populated State**: Lexicographically sorted `packetHash`es are hashed together with SHA-256. Equal digests provide cryptographically strong practical equality with negligible collision probability ($\approx 2^{-256}$).
- **16 Prefix Buckets**: Hashes are partitioned by their first hex character (`0`–`f`). When digests diverge, nodes compare bucket checksums to pinpoint exact divergent slices without transferring unaffected items.

#### 3. Anti-Entropy Protocol Sequence
When peers synchronize:
```text
STATE_SUMMARY (digest & bucket checksums)
     ↓
Compare root digest (O(1) summary exit on match)
     ↓
Compare 16 prefix bucket checksums
     ↓
BUCKET_HASH_EXCHANGE (authoritative full hashes for divergent buckets)
     ↓
Compute symmetric set differences (missingFromPeer / missingFromSelf)
     ↓
SYNC_REQUEST (batches of up to 50 packets)
     ↓
SYNC_RESPONSE (MeshPacket payloads)
     ↓
SYNC_ACK (certifies pairwise sync completion)
```

#### 4. Partition Resilience & Self-Healing
- **Partition Isolation**: Links or submeshes can be severed via `/api/mesh/partition`. Partitioned submeshes operate independently and achieve local consistency.
- **Bi-Directional Healing**: When links are restored via `/api/mesh/heal`, anti-entropy exchanges detect divergence and mutually stream missing transactions across the healed boundary.
- **Alternate-Peer Selection**: If a target peer times out or fails, the node aborts the session, marks the peer `DEGRADED`, and falls back to an alternate reachable neighbor.
- **TTL vs Anti-Entropy Independence**: TTL limits initial push broadcast radius. **TTL never blocks anti-entropy repair**; anti-entropy synchronizes packets even if their push TTL has expired.

#### 5. Preservation of Phase 3 Double-Spending Rules
Conflicting offline wallet transactions (e.g. same wallet ID and counter with differing nonces) are **never** discarded by mesh nodes. Both packets synchronize through the mesh to the bridge so the authoritative Phase 3 backend can detect the collision, flag `double_spend_counter_collision`, and freeze the wallet into `LOCKED_DISPUTED`.

#### 6. Explicit Distributed Systems Non-Goals
- **No Linearizability / Global Strong Consistency**: Convergence is eventual across connected components.
- **No Global Transaction Ordering**: Transactions from different senders are concurrent; strict monotonic order is enforced per wallet.
- **No Physical BLE / Hardware Guarantees**: This is an in-memory Java simulator; BLE MTU constraints and hardware secure enclaves are deferred to Phase 9.

---

### Problem 6: Fault Injection & Distributed Reliability Testing (Phase 5)

Distributed mesh systems and deferred settlement architectures face adverse network conditions, partitions, lost responses, and transient database conflicts. Phase 5 provides an in-memory, deterministic fault-injection and reliability-testing framework to validate that Phases 1–4 preserve all cryptographic, idempotency, sequence, and funds conservation invariants under failure.

#### 1. Financial Safety Boundary
Fault injection operates **strictly at transport, control, and exception boundaries**. The injector **never directly mutates** balances, escrow amounts, wallet counters, or database entities. All state transitions occur through standard domain pipelines.

#### 2. Supported Fault Types (`FaultType`)
The engine supports 13 deterministic fault types:
- `DROP`: Silently drops packets or sync messages in transit.
- `DUPLICATE`: Duplicates packets $N$ times over mesh links.
- `DELAY`: Withholds packets from push gossip for delayed delivery.
- `REORDER`: Inverts transmission order to verify existing Phase 3 `PENDING_SEQUENCE_GAP` handling.
- `PARTITION`: Severs links between submeshes or nodes.
- `PEER_UNAVAILABLE`: Simulates unreachable peer to test fallback alternate selection.
- `BRIDGE_UNAVAILABLE`: Simulates mesh-to-bridge transport failure while keeping mesh buffers intact.
- `MALFORMED_SYNC_MESSAGE`: Injects invalid schema/control sync messages.
- `CORRUPTED_PACKET_PAYLOAD`: Injects corrupted ciphertext bytes to verify decryption rejection.
- `TRANSIENT_DATABASE_FAILURE`: Injects `OptimisticLockException` to verify exponential backoff retries.
- `STALE_RESPONSE`: Drops post-commit HTTP responses to verify fast-path recovery without double debits.
- `DUPLICATE_REQUEST`: Concurrent submission of identical packet hashes.
- `CRASH_AND_RESTART`: Resets volatile in-memory node buffers (`clear()`) followed by full anti-entropy recovery.

#### 3. Machine-Checkable Invariants (I1–I12)
1. **I1 (Packet Identity)**: `packetHash == SHA-256(ciphertext)`.
2. **I2 (Transport Deduplication)**: At most 1 entry per packet hash in node buffer.
3. **I3 (Settlement Idempotency)**: At most 1 committed settlement per packet hash.
4. **I4 (Funds Conservation)**: $\sum \text{liquidBalance} + \sum \text{offlineLockedBalance} \equiv \text{InitialTotalFunds}$.
5. **I5 (Non-Negative Escrow)**: Offline wallet remaining escrow $\ge 0$.
6. **I6 (Observable Conflict)**: Conflicting counter collisions recorded as `CONFLICTING` and wallet frozen as `LOCKED_DISPUTED`.
7. **I7 (Connected Component Convergence)**: Connected devices achieve identical `stateDigest` after anti-entropy.
8. **I8 (TTL Independence)**: Anti-entropy repairs missing packets regardless of TTL expiration.
9. **I9 (Transient Recoverability)**: Transient DB errors release in-flight locks to allow retries.
10. **I10 (Permanent Terminality)**: Validation failures terminate without retry loops.
11. **I11 (Crash Non-Mutation)**: Node crash/restart does not alter backend ledger.
12. **I12 (Cryptographic Barrier)**: Unauthenticated or corrupted packets are unconditionally rejected.

---

## File-by-file walkthrough

```
upi-offline-mesh/
├── pom.xml                                  Maven build, Spring Boot 3.3, Java 17
├── mvnw, mvnw.cmd                           Maven wrapper (no install needed)
├── README.md                                this file
└── src/main/
    ├── resources/
    │   ├── application.properties           H2 in-memory DB, port 8080, TTLs
    │   └── templates/dashboard.html         The interactive demo UI
    └── java/com/demo/upimesh/
        ├── UpiMeshApplication.java          Spring Boot main class
        │
        ├── model/                           ── Domain layer
        │   ├── Account.java                 JPA entity. @Version = optimistic lock + offlineLockedBalance
        │   ├── AccountRepository.java       Spring Data JPA
        │   ├── OfflineWallet.java           JPA entity. Offline escrow allocation, epoch, counter, status
        │   ├── OfflineWalletRepository.java Spring Data JPA (findByWalletId)
        │   ├── OfflineWalletCertificate.java Server-signed offline spending capability certificate (record)
        │   ├── SettlementReceipt.java       Server-signed cryptographic settlement receipt (record)
        │   ├── Transaction.java             Settled-tx ledger. unique idx on packetHash, walletId, counter
        │   ├── TransactionRepository.java   Spring Data JPA (findByPacketHash, findByWalletIdAndSequenceCounter)
        │   ├── MeshPacket.java              Wire format. Outer fields readable, ciphertext opaque + getPacketHash()
        │   ├── PaymentInstruction.java      Decrypted payload (sender/receiver/amount/nonce/time/walletId/counter/cert)
        │   └── sync/                        ── Phase 4 Synchronization models
        │       ├── MeshSyncMessage.java     Sealed interface for typed sync message hierarchy
        │       ├── HelloMessage.java        Peer discovery and heartbeat record
        │       ├── StateSummaryMessage.java State digest and 16 prefix bucket checksums record
        │       ├── BucketHashExchangeMessage.java Full authoritative packet hashes for divergent bucket record
        │       ├── SyncRequestMessage.java  Bounded batch pull request record
        │       ├── SyncResponseMessage.java Payload delivery record
        │       ├── SyncAckMessage.java      Pairwise synchronization completion acknowledgment record
        │       ├── PacketSyncMeta.java      Stored packet synchronization metadata record
        │       └── PeerSyncRecord.java      Neighbor synchronization tracking class
        │
        ├── fault/                           ── Phase 5 Fault Injection & Reliability layer
        │   ├── FaultType.java               Enum of all 13 supported network, control, and persistence fault types
        │   ├── FaultRule.java               Immutable rule defining target criteria, occurrence limits, and message classes
        │   ├── FaultInterceptor.java        Narrow adapter interface for transport, sync, and settlement interception
        │   ├── FaultInjector.java           Central engine managing active rules, atomic counters, and zero-overhead passthrough
        │   └── ReliabilityMetrics.java      In-memory atomic metrics tracking fault events and invariant checks
        │
        ├── crypto/                          ── Cryptography layer
        │   ├── ServerKeyHolder.java         Generates RSA-2048 and Ed25519 issuer keypairs on startup
        │   ├── HybridCryptoService.java     RSA-OAEP + AES-256-GCM encrypt/decrypt + ciphertext hash
        │   └── SignatureService.java        Ed25519 signing, verification, cert/receipt signing, canonical serialization
        │
        ├── service/                         ── Business logic
        │   ├── DemoService.java             Seeds accounts, simulates phone creation of online & offline packets
        │   ├── VirtualDevice.java           Phone in mesh. packetHash store, state digest, 16 buckets, wallet state
        │   ├── AntiEntropyService.java      Pairwise anti-entropy reconciliation engine with fallback
        │   ├── MeshSimulatorService.java    Hybrid push-pull coordinator with partition/heal topology controls
        │   ├── OfflineWalletService.java    Escrow allocation, certificate issuance, and wallet reconciliation
        │   ├── IdempotencyService.java      In-flight concurrency gate (tryAcquire/release)
        │   ├── SettlementService.java       Fresh-transaction optimistic-lock retry, offline sequence state machine & fork detection
        │   ├── TransientSettlementException.java Dedicated exception on retry exhaustion
        │   └── BridgeIngestionService.java  THE pipeline: DB lookup → tryAcquire → decrypt → freshness → verify sig → cert check → settle
        │
        ├── controller/                      ── HTTP layer
        │   ├── ApiController.java           All REST endpoints (/api/mesh/sync, /api/mesh/partition, etc.)
        │   └── DashboardController.java     Serves the dashboard HTML at /
        │
        └── config/
            └── AppConfig.java               @EnableScheduling for cache eviction

src/test/java/com/demo/upimesh/
├── CryptographicIdentityTest.java           Sender identity & authorization verification integration tests (10 tests)
├── IdempotencyConcurrencyTest.java          The 3-bridges-at-once test + tamper test (3 tests)
├── ReliableIdempotencyTest.java             13 Phase 2 reliability, retry, lost-response, and concurrency tests
├── OfflineWalletReliabilityTest.java        20 Phase 3 offline escrow, sequence gaps, fork detection & receipt tests
├── AdvancedGossipSyncTest.java              15 Phase 4 anti-entropy, state digest, partition & convergence tests
├── DistributedReliabilityTest.java          25 Phase 5 deterministic network, bridge, compound, and property fault tests
└── crypto/
    └── SignatureServiceTest.java            Ed25519 unit tests & canonicalization determinism tests (10 tests)
```

---

## API reference

| Method | Path | What it does |
|---|---|---|
| GET | `/` | Dashboard HTML |
| GET | `/api/server-key` | Server's RSA public key (base64) |
| GET | `/api/accounts` | All accounts and balances |
| GET | `/api/transactions` | Last 20 transactions |
| GET | `/api/mesh/state` | Current state of every virtual device, digests, and severed links |
| POST | `/api/demo/send` | Simulate sender phone — encrypt + inject packet |
| POST | `/api/mesh/gossip` | Run one round of epidemic push gossip across the mesh |
| POST | `/api/mesh/sync` | **Phase 4:** Run pairwise anti-entropy synchronization across reachable peers |
| POST | `/api/mesh/partition` | **Phase 4:** Sever specific links or create submesh partitions |
| POST | `/api/mesh/heal` | **Phase 4:** Heal specific link or all partitioned mesh links |
| POST | `/api/mesh/flush` | Bridges with internet upload to backend (parallel) |
| POST | `/api/mesh/reset` | Clear mesh + idempotency cache |
| POST | `/api/bridge/ingest` | **The production endpoint.** Real bridges POST here |
| GET | `/h2-console` | Browse the in-memory database |

H2 console login: JDBC URL `jdbc:h2:mem:upimesh`, username `sa`, no password.

### Request format for `/api/bridge/ingest`

```http
POST /api/bridge/ingest
Content-Type: application/json
X-Bridge-Node-Id: phone-bridge-42
X-Hop-Count: 3

{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-RSA-and-AES-blob"
}
```

Response:
```json
{
  "outcome": "SETTLED",                     // or "DUPLICATE_DROPPED", "INVALID", "REJECTED", "TRANSIENT_FAILURE"
  "packetHash": "a3f8c9...",
  "reason": null,                            // populated on INVALID / REJECTED / TRANSIENT_FAILURE
  "transactionId": 42                        // populated on SETTLED or REJECTED
}
```

---

## Tests

Run all tests:
```
mvnw.cmd test
```

Key test suites:

- **`ReliableIdempotencyTest`** — 13 tests covering retry on transient failures, optimistic lock conflict recovery, 10-thread concurrent duplicate delivery, lost HTTP response recovery, balance conservation across concurrent payments, and database deduplication guarantees.
- **`CryptographicIdentityTest`** — 10 tests covering Ed25519 signature verification, forged signatures, cross-account keys, algorithm validation, and freshness boundaries.
- **`IdempotencyConcurrencyTest`** — 3 tests verifying parallel bridge uploads and ciphertext tamper resistance.
- **`SignatureServiceTest`** — Deterministic canonical serialization and digital signature unit tests.
- **`OfflineWalletReliabilityTest`** — 20 tests verifying escrow allocation, sequence gap state machine, fork detection, and receipt validation.
- **`AdvancedGossipSyncTest`** — 15 tests verifying pairwise anti-entropy, state digests, 16-bucket slicing, and partition healing.
- **`DistributedReliabilityTest`** — 25 tests verifying deterministic fault injection and property preservation.
- **`DashboardApiControllerTest`** — 7 integration tests verifying Phase 6 REST API endpoints, DTO contracts, input validation, and single-rule deletion.

---

## Phase 6: Real-Time React Distributed Payment Dashboard

Phase 6 introduces a production-style React dashboard acting as a **pure visualization and control layer** over the existing Spring Boot virtual mesh simulator.

### Architecture & Boundaries

```text
┌────────────────────────────────────────────────────────┐
│      React Dashboard (Vite + TypeScript + Tailwind)    │
│  - Adaptive Polling (2s active / 10s background)       │
│  - Stale state detection (>5s) & disabled controls     │
│  - Zero business logic / balance calculations in React │
└───────────────────────────┬────────────────────────────┘
                            │ HTTP Polling (Port 5173 -> 8080)
                            ▼
┌────────────────────────────────────────────────────────┐
│      Spring Boot Backend (Port 8080)                   │
│  - DashboardApiController (typed DTO layer)            │
│  - InvariantAuditService (authoritative I1-I12 audit)  │
│  - Strict Fault API input validation                   │
│  - MeshSimulatorService & AntiEntropyService           │
│  - SettlementService & OfflineWalletService            │
└────────────────────────────────────────────────────────┘
```

- **Strict Boundary**: The React dashboard never computes escrow, balances, sequence counters, hashes, or invariant outcomes. All calculations are performed authoritatively by the Spring Boot backend.
- **Transport**: Adaptive polling is used exclusively (no WebSockets or SSE). The dashboard polls every 2000 ms when the browser tab is active, backs off to 10000 ms when hidden, and performs immediate refetches upon user mutations.
- **Stale Data Safety**: If backend polling fails for more than ~5000 ms, the UI displays a prominent `STALE DATA` warning and automatically disables all mutation controls (partition, heal, inject, reset) to prevent inconsistent state transitions.

### Dashboard Pages

1. **Overview (`/`)**: High-level aggregate KPIs: system health status, total devices, online bridges, convergence state, total held packets, liquid and escrow fund totals, active faults, and invariant violation counters.
2. **Mesh Topology (`/mesh`)**: Interactive visual topology canvas displaying simulated device nodes (with deterministic coordinates and dynamic circular fallback), connection links (active, severed, syncing), packet counts, and truncated state digests. Clicking any node opens an on-demand inspection drawer with 16 bucket checksums, held packet details, and peer synchronization tables.
3. **Wallets (`/wallets`)**: Authoritative offline wallet registry and demo bank accounts showing escrow allocations, settled amounts, remaining allowances, and account liquid balances. Supports modal escrow allocations and wallet reconciliation/closure.
4. **Transactions (`/transactions`)**: Server-paginated and status-filtered transaction explorer with packet hash search, cryptographic receipt viewer modal, and conflict reason inspection.
5. **Reliability & Invariants (`/reliability`)**: Real-time reliability metric gauges (injections, drops, duplicates, retries, recoveries) and authoritative server evaluations of machine-checkable Invariants **I1 through I12**.
6. **Fault Injection Lab (`/faults`)**: Deterministic fault-injection workspace with preset buttons (4G bridge outage, network partition, drop burst, transient DB error), custom rule creator with strict backend validation, active rules table with individual rule deletion, injector pause/resume toggle, and reset button. Displays a prominent **"SIMULATION ONLY — Fault injection operates exclusively on simulated mesh and bridge transport layers"** warning banner.

---

## Phase 7 — PostgreSQL + Redis Infrastructure

### Core Principle
> **"PostgreSQL is the authoritative financial store. Redis is non-authoritative coordination/cache."**

### Architecture Highlights
1. **Authoritative Persistence (PostgreSQL 16 + Flyway)**:
   - All financial ledgers, account balances, offline wallet escrows, monotonic sequence counters, and transaction histories reside authoritatively in PostgreSQL.
   - Managed via deterministic Flyway versioned migrations (`V1__initial_schema.sql`).
   - Hardened with database-level constraints:
     - `UNIQUE(packet_hash)` on the `transactions` table.
     - `CHECK (balance >= 0)` and `CHECK (offline_locked_balance >= 0)` on `accounts`.
     - `CHECK (settled_amount + remaining_amount <= allocated_amount)` on `offline_wallets`.
     - Monetary amounts strictly formatted as `NUMERIC(19, 2)`.
     - Foreign key referential integrity across all relationships with `ON DELETE SET NULL` on self-referencing winning transaction pointers.
   - Production JPA configuration uses `ddl-auto=validate` to prevent any runtime schema modifications.
2. **Distributed Coordination & Caching (Redis 7)**:
   - Redis operates purely as a transient coordination gate and read cache:
     - **Distributed Lock Key**: `upi:lock:<packetHash>` with an explicit 60-second in-flight TTL (and 24-hour completion TTL).
     - **Dashboard Read Cache Keys**: `upi:cache:dashboard:overview`, `upi:cache:dashboard:mesh`, `upi:cache:dashboard:reliability` with an explicit 10-second TTL.
   - **Mutation-Driven Cache Invalidation**: Caches are immediately invalidated upon:
     - Financial transaction settlements, rejections, or conflicts.
     - Offline wallet escrow allocations or reconciliations.
     - Mesh topology changes (partitioning, healing, flushing, syncing).
     - Fault injector rule registrations, removals, toggles, or resets.
3. **Resilient Failover & Non-Authoritative Fallback**:
   - If Redis is unavailable, offline, or times out:
     - `IdempotencyService` catches connection exceptions, increments `redisFallbackTotal`, and falls back to the local in-flight gate.
     - Requests still reach the authoritative PostgreSQL database barrier.
     - Duplicate packets are stopped by PostgreSQL `UNIQUE(packet_hash)` without risk of double-debiting.
     - Redis failure NEVER compromises financial correctness.
4. **Restart Durability Tested**:
   - Verified via `RestartDurabilityIntegrationTest`: Even if the application context is completely shut down and destroyed, all balances, escrow states, settled transactions, and idempotency deduplication barriers survive upon restart.

### Local Development Setup (Docker Compose)

Start PostgreSQL 16 and Redis 7 in containers with persistent storage and healthchecks:

```bash
# Start infrastructure
docker compose up -d

# Run backend with PostgreSQL + Redis
mvnw.cmd spring-boot:run

# Shutdown infrastructure without losing data
docker compose down
```

### Environment Variables

| Variable | Description | Local Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/upimesh` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password | `postgres` |
| `SPRING_DATA_REDIS_HOST` | Redis host | `localhost` |
| `SPRING_DATA_REDIS_PORT` | Redis port | `6379` |
| `SPRING_DATA_REDIS_PASSWORD` | Redis password (if required) | *empty* |

---

## Observability, Metrics & Production Diagnostics (Phase 8)

### Architectural Invariant: Observational Telemetry vs. Authoritative Truth
- **Total System Escrow Metric Is Observational Only:** The `upi.wallets.escrow.total.allocated` metric is telemetry intended exclusively for operational dashboarding and alerting. It must **never** be treated as authoritative financial state. Exact financial balances and escrow truth reside solely in PostgreSQL.
- **Strict Bounded Cardinality:** No high-cardinality values (`packetHash`, `transactionId`, `walletId`, `ownerVpa`, `requestId`, `nonce`) are ever used as metric tags. Labels are strictly restricted to bounded enums (`status`, `reason`, `mode`, `stage`, `fault_type`, `device_role`, `result`).
- **Data Protection & Secure Actuator:** Actuator endpoint security is enforced (`management.endpoint.health.show-details=when_authorized`). Detailed infrastructure secrets, passwords, and private keys are never exposed in metrics, actuator endpoints, or log streams.

### Actuator Endpoints & Health Model

| Endpoint | Purpose | Access Policy |
|---|---|---|
| `GET /actuator/health` | Service health status (`UP`, `DEGRADED`, `DOWN`) | Public (sanitized without authorization) |
| `GET /actuator/prometheus` | Prometheus exposition format metrics | Public / Scraped by Prometheus |
| `GET /actuator/metrics` | Micrometer metric catalog discovery | Diagnostic |
| `GET /actuator/info` | Application build & version information | Public |

- **Health Status States:**
  - `UP` (HTTP 200): PostgreSQL and Redis are both healthy and responsive.
  - `DEGRADED` (HTTP 200): PostgreSQL is healthy, Redis is unavailable. System operates in non-authoritative fallback mode without data loss.
  - `DOWN` (HTTP 503): PostgreSQL is unreachable. Authoritative financial operations are safely halted.

### Production Monitoring Stack (Prometheus & Grafana)

The complete observability stack is provisioned in `docker-compose.yml`:

```bash
# Start PostgreSQL, Redis, Prometheus (9090), and Grafana (3000)
docker compose up -d

# Open Grafana
http://localhost:3000   (credentials: admin / admin)

# Open Prometheus Web UI & Alert Manager
http://localhost:9090
```

#### Pre-Provisioned Grafana Dashboards
1. **01 - System Overview:** High-level throughput, active faults, node count, and invariant violation counters.
2. **02 - Transactions & Settlement:** Attempted, settled, duplicate, and rejection rates alongside P50, P95, and P99 latency percentiles.
3. **03 - Wallets & Escrow:** Wallet lifecycle counts, reconciliations, disputed states, and observational escrow totals.
4. **04 - Mesh & Gossip Convergence:** Packet reception, gossip hop distribution, network partition events, and anti-entropy sync results.
5. **05 - Reliability & Fault Injection:** Active simulated fault injections, recovery rates, and invariant violation audits.
6. **06 - Infrastructure & Cache:** PostgreSQL connection pool, Redis operations, fallback rates, and dashboard cache hit/miss ratios.

#### Operational Alert Rules (`monitoring/prometheus/alert_rules.yml`)
- `PostgresUnavailable` (Critical): Triggers if PostgreSQL health check fails for >1 minute.
- `RedisDegraded` (Warning): Triggers if Redis is disconnected and non-authoritative fallback is active for >2 minutes.
- `HighSettlementLatency` (Warning): Triggers if P99 settlement latency exceeds 500ms over a 5-minute window.
- `HighTransactionRejectionRate` (Warning): Triggers if rejection rate exceeds 15% of attempted traffic.
- `ConflictingTransactionsDetected` (Critical): Triggers immediately if a sequence counter re-use/double-spend is detected.
- `InvariantViolationDetected` (Critical): Triggers immediately if any of invariants I1–I12 fail.
- `MeshConvergenceFailure` (Warning): Triggers if anti-entropy sync divergence is detected.
- `ExcessiveDbRetries` (Warning): Triggers if optimistic locking retries exceed 5 per second.

### Correlation IDs & Structured Logging
- **`X-Request-ID` Header:** Injected automatically into all incoming HTTP requests via `CorrelationIdFilter` if not already provided.
- **MDC Propagation:** Set in SLF4J MDC `[req:<requestId>]` across the request thread lifecycle.
- **Response Header:** Echoed back in the HTTP response `X-Request-ID` header for end-to-end tracing.

---

## What's NOT real (and what would change for production)

This is a teaching demo. To make it production-grade you'd swap these things:

| What's in the demo | What it would be in production |
|---|---|
| H2 in-memory DB | PostgreSQL / MySQL with replicas |
| `ConcurrentHashMap` for idempotency | Redis with `SET NX EX` |
| RSA keypair regenerated on every startup | Private key in HSM (AWS KMS, HashiCorp Vault). Public key cached on devices. |
| Server-side `DemoService.createPacket()` | Same code running on Android, in a Kotlin port |
| Software-simulated mesh (`MeshSimulatorService`) | Real BLE GATT or Wi-Fi Direct between phones |
| One settlement service that owns the ledger | Integration with NPCI / a real bank core |
| No auth on `/api/bridge/ingest` | Mutual TLS or signed bridge-node certificates |
| In-memory accounts seeded on startup | Real KYC'd users, real VPAs, real PIN verification against the bank |
| In-memory simulated device monotonic counter | Hardware-backed monotonic counter (e.g. Android StrongBox / eSE) |
| Software-signed wallet certificate | Hardware Attestation + CA-backed certificate chain |
| H2 console exposed | Disabled |
| No rate limiting | Per-bridge-node rate limit, per-sender velocity check |
| Logs to console | Structured logs to a SIEM, alerts on `INVALID` spikes |

The cryptography, idempotency, and offline escrow state machine code is essentially production-shaped. The hardware isolation and distributed infrastructure around it is what changes.

---

## Honest limitations of the concept & Software vs Hardware Boundary

Let's be completely transparent about the security boundary:

1. **Software vs Hardware Boundary (Anti-Cloning)**:
   - In this prototype, devices and sequence counters are simulated in software. A software-only implementation running on an untrusted device **cannot provide absolute physical anti-cloning guarantees**. If an attacker clones the application memory or performs a VM snapshot rollback, they could produce two validly signed transactions sharing the same sequence counter before syncing.
   - **Production Requirement**: Real-world rollback resistance and anti-cloning require **hardware-backed non-exportable private keys and monotonic counters** provided by secure elements (such as Android StrongBox Keymaster, Embedded Secure Element (eSE), or Apple Secure Enclave) combined with remote hardware attestation.
2. **Detection vs Prevention**:
   - Monotonic sequence checks and escrow accounting strictly **bound the issuer's authorized exposure** to the pre-funded escrow amount.
   - Conflicting offline spends cannot be prevented at the offline peer if the device is cloned; instead, **conflicts are detected with mathematical certainty during reconciliation**, freezing the wallet into `LOCKED_DISPUTED` and creating an auditable dispute record.
3. **Bluetooth in real life is hard**:
   - Background BLE on Android is heavily throttled since Android 8. iOS peripheral mode is locked down. Two strangers' phones reliably forming a GATT connection while the apps aren't actively open is genuinely difficult and energy intensive. This prototype simulates the mesh layer.
4. **Terminal transfer restriction**:
   - Offline funds cannot be recursively re-spent across arbitrary peer chains without global consensus or hardware-enforced token transfer. Transfers are strictly terminal: Payer $\to$ Payee $\to$ Backend.

For a college / portfolio project: name the concept honestly as **"mesh-routed deferred settlement"** rather than "real-time offline UPI," and you'll have a much stronger pitch. The cryptography and idempotency work here is real engineering and worth showing off.

---

## Troubleshooting

**`java: command not found`** — Install JDK 17+. On Windows, `winget install EclipseAdoptium.Temurin.17.JDK` or download from adoptium.net.

**Port 8080 already in use** — Change `server.port` in `application.properties`.

**First `mvnw.cmd` run hangs for a long time** — It's downloading Maven (~10 MB) then dependencies (~80 MB). Give it 2–3 minutes on a normal connection. After that, startup is ~5 seconds.

**`mvnw.cmd : The term 'mvnw.cmd' is not recognized`** — On PowerShell you need to prefix with `.\`: `.\mvnw.cmd spring-boot:run`.

**Tests fail intermittently** — The concurrency test is timing-sensitive. If it ever flakes, run it 3x; if it consistently fails on your hardware, file the actual failure output.

---

## License

Demo code, no license. Use it however you want for learning.
