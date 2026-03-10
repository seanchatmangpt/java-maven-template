# OTP ↔ EIP Equivalence Table

Complete mapping between Erlang/OTP primitives and the 39 EIP patterns implemented in JOTP.

## Core Primitive → Pattern Mapping

| OTP Primitive | JOTP Class | EIP Patterns Enabled |
|---|---|---|
| `spawn/3` + mailbox | `Proc<S,M>` | All stateful patterns (Aggregator, Resequencer, DurableSubscriber, PollingConsumer) |
| `Pid ! Msg` | `proc.tell(msg)` | Message send to any channel |
| `gen_server:call/2` | `proc.ask(msg)` | Request-Reply (10), Return Address (11) |
| `gen_event` | `EventManager<E>` | Publish-Subscribe (02), Message Bus (04) |
| `supervisor` | `Supervisor` | Process Manager (22), fault recovery |
| `link/1` | `ProcessLink` | Cascade failure propagation |
| `monitor/2` | `ProcessMonitor` | Durable Subscriber (05) lifecycle |
| `global:register_name` | `ProcessRegistry` | Service registry, named endpoints |
| `timer:send_after` | `ProcTimer` | Message Expiration (13) |
| `sys:get_state` | `ProcSys.getState` | Channel introspection |
| `sys:suspend/resume` | `ProcSys.suspend/resume` | Hot code reload, DurableSubscriber pause |
| `sys:statistics` | `ProcSys.statistics` | Message throughput monitoring |
| `proc_lib:start_link` | `ProcLib` | Service Activator (35) startup handshake |
| `pmap` | `Parallel.all` | Scatter-Gather (21) |
| `process_flag(trap_exit)` | `Proc.trapExits(true)` | Exit signal handling |

## EIP Pattern → JOTP Implementation Table

| # | EIP Pattern | JOTP Class | OTP Equivalent |
|---|---|---|---|
| 01 | Point-to-Point Channel | `PointToPointChannel<T>` | Process mailbox |
| 02 | Publish-Subscribe Channel | `PublishSubscribeChannel<T>` → `EventManager<T>` | `gen_event` |
| 03 | Dead Letter Channel | `DeadLetterChannel<T>` | Catch-all receive clause |
| 04 | Message Bus | `EventManager<E>` | `gen_event` process |
| 05 | Durable Subscriber | `DurableSubscriber<T>` → `Proc<State,Cmd>` | `sys:suspend/resume` |
| 06 | Datatype Channel | `MessageChannel<T>` (typed) | Typed process messages |
| 07 | Command Message | `record Cmd(…) implements Msg` | Message tuple pattern |
| 08 | Document Message | `record Doc(…) implements Msg` | Message tuple |
| 09 | Event Message | `EventManager<E>.notify(e)` | `gen_event:notify` |
| 10 | Request-Reply | `proc.ask(msg)` | `gen_server:call` |
| 11 | Return Address | `CompletableFuture<T>` in record | `{call, From, Req}` From |
| 12 | Correlation Identifier | `UUID` in sealed msg record | Message reference |
| 13 | Message Sequence | `record Seq(int n, T payload)` | Sequence number in state |
| 14 | Content-Based Router | `MessageRouter<T>` | Selective receive guards |
| 15 | Dynamic Router | `DynamicRouter<T>` (COWAL table) | Live routing table in gen_server state |
| 16 | Message Filter | `MessageFilter<T>` | Selective receive |
| 17 | Recipient List | `PublishSubscribeChannel<T>` | `gen_event` multi-handler |
| 18 | Splitter | `MessageSplitter<T,U>` | `lists:foreach` in handler |
| 19 | Aggregator | `MessageAggregator<T,R>` | Stateful gen_server map |
| 20 | Resequencer | `Resequencer<T,K>` → `Proc<State,Entry>` | gen_server + gb_trees |
| 21 | Scatter-Gather | `Parallel.all(tasks)` | `StructuredTaskScope` / pmap |
| 22 | Process Manager | `Supervisor` + `StateMachine` | OTP Saga pattern |
| 23 | Message Translator | `MessageTransformer<T,U>` | Pure handler function |
| 24 | Envelope Wrapper | Record wrapping | Tagged tuple |
| 25 | Content Enricher | `ContentEnricher<T,R,U>` | Handler with external lookup |
| 26 | Content Filter | `MessageFilter<T>` (field projection) | Pattern matching |
| 27 | Claim Check | `ConcurrentHashMap` + reference key | ETS table |
| 28 | Normalizer | `MessageTransformer` per source type | Per-handler translation |
| 29 | Polling Consumer | `PollingConsumer<T>` → `Proc<Long,T>` | `receive after Timeout` |
| 30 | Event-Driven Consumer | `PointToPointChannel<T>` | Process mailbox receive |
| 31 | Competing Consumers | `MessageDispatcher<T>` (N workers) | Supervisor worker pool |
| 32 | Message Dispatcher | `MessageDispatcher<T>` | Shared queue + worker pool |
| 33 | Selective Consumer | `MessageFilter<T>` + `Proc` | Selective receive |
| 34 | Idempotent Receiver | `Proc<HashSet<ID>,Msg>` | Dedup state in gen_server |
| 35 | Service Activator | `Proc<Count,Msg>` | gen_server handle_call |
| 36 | Channel Adapter | Virtual thread bridge | Port driver |
| 37 | Wire Tap | `WireTap<T>` (virtual thread fork) | `sys:trace/2` |
| 38 | Message Store | `MessageStore` | ETS table |
| 39 | Message History | Record with history list | Annotated message |

## Key Design Differences

| Aspect | Erlang/OTP | JOTP |
|---|---|---|
| Process creation | `spawn/3` | `new Proc<>(state, handler)` |
| Message type safety | Dynamic atoms | Sealed interface + record types |
| Pattern matching | Guard clauses | Switch expressions |
| State | Process dictionary + stack | Immutable `S` parameter |
| Crash notification | `'EXIT'` signal | `crashCallback` / `ProcessMonitor` |
| Hot code upgrade | `code:load_file` | `ProcSys.suspend` + swap + `resume` |
| Distribution | `node@host` | Not included (single JVM) |
