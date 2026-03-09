# Explanation: Understanding the OTP–Java 26 Library

This section of the documentation is dedicated to **understanding** — building the mental models, conceptual frameworks, and design rationale that let you use the library with genuine comprehension rather than cargo-culting its patterns.

These articles do not teach you how to do specific tasks (that is the how-to section). They explain *why* the library is designed the way it is, *what* conceptual tradition it draws from, and *how* the pieces fit together philosophically.

---

## Who this is for

These articles are written for experienced Java developers who:

- Are comfortable with Java concurrency primitives (`ExecutorService`, `CompletableFuture`, `synchronized`)
- May have heard of Erlang or Elixir but have not worked with them seriously
- Want to understand the *ideas* behind the library, not just the API surface
- Are evaluating whether this approach is right for their system

You do not need prior Erlang or OTP knowledge. Where Erlang concepts appear, they are explained from first principles.

---

## Articles in this section

### [Why bring OTP to Java?](why-otp-java.md)

The philosophical and practical case for importing Erlang's fault-tolerance model into the Java ecosystem. What problems does traditional Java concurrency fail to solve? Why is OTP's answer the right one? Why now, with Java 26?

### [Virtual threads as Erlang processes](virtual-threads.md)

How Java 26 virtual threads map conceptually and mechanically to Erlang processes. Covers spawn, mailboxes, process isolation, and the opaque ProcRef handle. Explains why millions of virtual threads are not the same as millions of OS threads.

### [The "let it crash" philosophy](let-it-crash.md)

The single most important idea in the Erlang tradition — and the hardest to accept for Java developers trained on defensive exception handling. What does it mean to let a process crash? Why does it produce more reliable software than try/catch everywhere?

### [Supervision trees](supervision-trees.md)

How supervisors contain failure and restart processes. The three restart strategies — ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE — and when each is appropriate. How to design a supervision tree that matches your system's failure semantics.

### [Java 26 features the library exploits](java26-features.md)

Sealed types, records, pattern matching, virtual threads, structured concurrency, and JPMS — a conceptual tour of how each Java 26 feature serves the OTP model. Understanding these connections explains why the library looks the way it does.

### [Formal equivalence: the twelve pillars](formal-equivalence.md)

A summary of the formal equivalence proof between OTP's architectural primitives and Java 26 constructs. Each of the twelve pillars is explained in plain language, with the key insight that makes the equivalence hold.

---

## How to read this section

These articles are not sequential — each stands alone. If you are entirely new to OTP concepts, a natural reading order is:

1. **Why OTP?** — establishes the problem space
2. **Virtual threads** — the foundational implementation mechanism
3. **Let it crash** — the philosophical shift
4. **Supervision trees** — the structural consequence of that philosophy
5. **Java 26 features** — the language machinery
6. **Formal equivalence** — the synthesis

If you are coming from Akka or a reactive framework, start with **Why OTP?** which explicitly addresses why those approaches fall short of the OTP model.

If you are an Erlang or Elixir developer evaluating this library as a migration target, start with **Formal equivalence** and then **Virtual threads** for the mapping details.

---

## The core thesis in one paragraph

Erlang/OTP spent thirty years solving a problem Java has largely ignored: how do you build a system that keeps running correctly when individual components fail? OTP's answer is architectural — processes are cheap, isolated, and supervised. Java 26, with virtual threads, sealed types, and structured concurrency, finally provides all the primitives needed to implement this architecture idiomatically. This library does exactly that: it maps the fifteen OTP behaviors to Java 26 constructs with formal precision, giving Java developers access to thirty years of battle-tested fault-tolerance wisdom without leaving the JVM ecosystem.
