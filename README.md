# Besu SLOAD Cache Analysis Plugin

A Besu plugin that traces every `SLOAD` opcode during block import, classifies each storage read as **cold** (first access, 2100 gas) or **warm** (re-access, 100 gas), and provides real-time per-block analytics via JSON-RPC and an embedded web dashboard.

## Features

- **Live SLOAD tracing** during block import via `BlockImportTracerProvider`
- **Per-block breakdown** by contract: total reads, cold/warm counts, percentages
- **Contract name resolution** via Etherscan V2 API (cached in memory, rate-limited)
- **JSON-RPC endpoints** under the `cache` namespace
- **Web dashboard** with sortable tables, per-slot drill-down, and auto-refresh
- **Zero Besu modifications** — drop the JAR into `plugins/` and go

## Build

```bash
./gradlew build
```

The plugin JAR is at `build/libs/besu-cache-plugin-snapshot.jar`.

## Deploy

```bash
cp build/libs/besu-cache-plugin-snapshot.jar $BESU_HOME/plugins/
```

Start Besu normally. The plugin auto-registers.

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `CACHE_PLUGIN_ETHERSCAN_KEY` | *(none)* | Etherscan API key for contract name resolution |
| `CACHE_PLUGIN_WEB_PORT` | `8547` | Port for the embedded web UI |
| `CACHE_PLUGIN_MAX_BLOCKS` | `1000` | Max blocks to keep in memory (ring buffer) |

## Besu startup flags

Enable the `cache` RPC namespace:

```bash
--rpc-http-api=ETH,NET,WEB3,CACHE
```

## JSON-RPC Endpoints

| Method | Params | Description |
|--------|--------|-------------|
| `cache_getBlockAnalysis` | `blockNumber` | Per-account SLOAD breakdown for a block |
| `cache_getBlockSloads` | `blockNumber`, `[address]` | Per-SLOAD detail, optionally filtered by contract |
| `cache_getRecentBlocks` | `[count]` | Summary of last N blocks |
| `cache_getContractName` | `address` | Cached contract name |
| `cache_getStatus` | *(none)* | Plugin status (blocks stored, names cached) |

## Web Dashboard

Open `http://localhost:8547` in a browser. Features:

- Block number lookup (or "latest")
- Sortable per-contract table (total, cold, warm, percentages)
- Click a contract row to expand per-slot details
- Auto-refresh mode to follow chain head
- Summary cards: total SLOADs, cold ratio, unique contracts

## How it works

1. Plugin registers a `BlockImportTracerProvider` during Besu startup
2. For each block, `AbstractBlockProcessor` calls our tracer
3. `tracePreExecution` captures the contract address and slot key when opcode is `0x54` (SLOAD)
4. `tracePostExecution` reads the gas cost to classify: >200 gas = cold (first access), ≤200 gas = warm
5. `traceEndBlock` aggregates results and stores them
6. Results are available via JSON-RPC and the web UI immediately

## Compatibility

Built against Besu `26.2.0`. Compatible with any Besu version that has the `BlockImportTracerProvider` service.
