# Examples: Message Passing Example

Complete example of request-reply and inter-process communication patterns.

## Request-Reply Pattern

This example demonstrates synchronous message exchange between processes.

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class MessagePassingExample {
    // ==================== Bank Account Service ====================

    sealed interface AccountMsg permits
        WithdrawMsg, DepositMsg, BalanceMsg, TransferMsg {}

    record WithdrawMsg(int amount,
        ProcRef<Boolean, AccountMsg> replyTo) implements AccountMsg {}
    record DepositMsg(int amount) implements AccountMsg {}
    record BalanceMsg(ProcRef<Integer, AccountMsg> replyTo) implements AccountMsg {}
    record TransferMsg(int amount,
        ProcRef<Void, AccountMsg> destination,
        ProcRef<Boolean, AccountMsg> replyTo) implements AccountMsg {}

    static class BankAccount {
        static ProcRef<Integer, AccountMsg> create(String name, int initial) {
            System.out.println("Creating account: " + name);
            return Proc.start(
                balance -> msg -> switch(msg) {
                    case WithdrawMsg w -> {
                        boolean success = balance >= w.amount();
                        int newBalance = success ? balance - w.amount() : balance;
                        w.replyTo().send(success);
                        yield newBalance;
                    }
                    case DepositMsg d -> {
                        System.out.println(name + ": +$" + d.amount());
                        yield balance + d.amount();
                    }
                    case BalanceMsg b -> {
                        b.replyTo().send(balance);
                        yield balance;
                    }
                    case TransferMsg t -> {
                        if (balance >= t.amount()) {
                            // Send deposit to destination
                            t.destination().send(new DepositMsg(t.amount()));
                            t.replyTo().send(true);
                            yield balance - t.amount();
                        } else {
                            t.replyTo().send(false);
                            yield balance;
                        }
                    }
                },
                initial
            );
        }
    }

    // ==================== Main: Two Accounts with Transfers ====================

    public static void main(String[] args) throws Exception {
        // Create two accounts
        var checking = BankAccount.create("Checking", 1000);
        var savings = BankAccount.create("Savings", 500);

        System.out.println("\n=== Initial Balances ===");
        int checkingBalance = checking.ask(
            replyTo -> new BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );
        int savingsBalance = savings.ask(
            replyTo -> new BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Checking: $" + checkingBalance);
        System.out.println("Savings: $" + savingsBalance);

        System.out.println("\n=== Transfer $200 from Checking to Savings ===");
        boolean success = checking.ask(
            replyTo -> new TransferMsg(200, savings, replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Transfer successful: " + success);

        // Give async deposit time to process
        Thread.sleep(100);

        System.out.println("\n=== Final Balances ===");
        checkingBalance = checking.ask(
            replyTo -> new BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );
        savingsBalance = savings.ask(
            replyTo -> new BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Checking: $" + checkingBalance);  // 800
        System.out.println("Savings: $" + savingsBalance);    // 700

        System.out.println("\n=== Try to withdraw more than available ===");
        boolean withdrew = checking.ask(
            replyTo -> new WithdrawMsg(1000, replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Withdrawal successful: " + withdrew);  // false
    }
}
```

## With Tests

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class MessagePassingTest {
    @Test
    void testRequestReplyBalance() throws Exception {
        var account = MessagePassingExample.BankAccount.create("Test", 100);

        int balance = account.ask(
            replyTo -> new MessagePassingExample.BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(balance).isEqualTo(100);
    }

    @Test
    void testWithdrawal() throws Exception {
        var account = MessagePassingExample.BankAccount.create("Test", 100);

        boolean success = account.ask(
            replyTo -> new MessagePassingExample.WithdrawMsg(50, replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(success).isTrue();

        int balance = account.ask(
            replyTo -> new MessagePassingExample.BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(balance).isEqualTo(50);
    }

    @Test
    void testInsufficientFunds() throws Exception {
        var account = MessagePassingExample.BankAccount.create("Test", 50);

        boolean success = account.ask(
            replyTo -> new MessagePassingExample.WithdrawMsg(100, replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(success).isFalse();

        // Balance should be unchanged
        int balance = account.ask(
            replyTo -> new MessagePassingExample.BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(balance).isEqualTo(50);
    }

    @Test
    void testTransferBetweenAccounts() throws Exception {
        var checking = MessagePassingExample.BankAccount.create("Checking", 100);
        var savings = MessagePassingExample.BankAccount.create("Savings", 50);

        boolean success = checking.ask(
            replyTo -> new MessagePassingExample.TransferMsg(30, savings, replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(success).isTrue();

        // Give async deposit time
        Thread.sleep(50);

        int checkingBalance = checking.ask(
            replyTo -> new MessagePassingExample.BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );
        int savingsBalance = savings.ask(
            replyTo -> new MessagePassingExample.BalanceMsg(replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(checkingBalance).isEqualTo(70);
        assertThat(savingsBalance).isEqualTo(80);
    }
}
```

## Key Patterns Demonstrated

1. **Request-Reply** — `ask()` for synchronous message exchange
2. **Fire-and-Forget** — `send()` for async operations (Deposit)
3. **Process References** — Passing `ProcRef` to enable inter-process messaging
4. **State Transitions** — Handler updates state based on messages
5. **Reply Channels** — Embedding reply target in request message

## What's Next?

- **[Basic Process Example](basic-process-example.md)** — Simple counter
- **[Supervision Tree Example](supervision-tree-example.md)** — Multi-service system
- **[How-To: Send & Receive Messages](../how-to/send-receive-messages.md)** — Advanced patterns
- **[Tutorial: Your First Process](../tutorials/02-first-process.md)** — Detailed walkthrough

---

**See Also:** [Reference: Proc API](../reference/api-proc.md) | [Reference: Glossary](../reference/glossary.md)
