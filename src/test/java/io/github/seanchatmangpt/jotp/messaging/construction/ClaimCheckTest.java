package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.acme.Result;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ClaimCheck} — lightweight claim token pattern.
 *
 * <p>ClaimCheck enables deferred loading and bandwidth optimization in distributed systems.
 * This test suite covers storage, retrieval, release, and error cases.
 */
@Timeout(5)
class ClaimCheckTest implements WithAssertions {

  @BeforeEach
  void setUp() {
    ClaimCheck.clearAll();
  }

  @AfterEach
  void tearDown() {
    ClaimCheck.clearAll();
  }

  // ── Basic operations ─────────────────────────────────────────────────

  @Test
  void claimCheckStoresPayloadAndReturnsToken() {
    String payload = "Large document content...";
    Map<String, String> metadata = Map.of("type", "document");

    ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck(payload, metadata);

    assertThat(checked.claimId()).isNotNull();
    assertThat(checked.timestamp()).isGreaterThan(0);
    assertThat(checked.metadata()).isEqualTo(metadata);
  }

  @Test
  void retrieveReturnsStoredPayload() {
    String payload = "Test document";
    UUID claimId = ClaimCheck.claimCheck(payload).claimId();

    Result<String, String> result = ClaimCheck.retrieve(claimId);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(s -> s, e -> null)).isEqualTo(payload);
  }

  @Test
  void retrieveNonExistentClaimReturnsError() {
    UUID nonExistentId = UUID.randomUUID();

    Result<String, String> result = ClaimCheck.retrieve(nonExistentId);

    assertThat(result.isFailure()).isTrue();
    assertThat(result.fold(s -> null, e -> e)).contains("No payload found");
  }

  // ── Claim management ─────────────────────────────────────────────────

  @Test
  void releaseRemovesClaimFromStorage() {
    String payload = "Document";
    UUID claimId = ClaimCheck.claimCheck(payload).claimId();

    boolean released = ClaimCheck.release(claimId);

    assertThat(released).isTrue();
    assertThat(ClaimCheck.retrieve(claimId).isFailure()).isTrue();
  }

  @Test
  void releaseNonExistentClaimReturnsFalse() {
    UUID nonExistentId = UUID.randomUUID();

    boolean released = ClaimCheck.release(nonExistentId);

    assertThat(released).isFalse();
  }

  @Test
  void existsChecksPresentClaim() {
    String payload = "Document";
    UUID claimId = ClaimCheck.claimCheck(payload).claimId();

    assertThat(ClaimCheck.exists(claimId)).isTrue();
  }

  @Test
  void existsReturnsFalseForMissingClaim() {
    UUID nonExistentId = UUID.randomUUID();

    assertThat(ClaimCheck.exists(nonExistentId)).isFalse();
  }

  // ── Single-use/consume pattern ───────────────────────────────────────

  @Test
  void consumeClaimRetrievesAndRemoves() {
    String payload = "Document";
    UUID claimId = ClaimCheck.claimCheck(payload).claimId();

    Result<String, String> result = ClaimCheck.consumeClaim(claimId);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(s -> s, e -> null)).isEqualTo(payload);
    assertThat(ClaimCheck.exists(claimId)).isFalse();
  }

  @Test
  void consumeNonExistentClaimReturnsError() {
    UUID nonExistentId = UUID.randomUUID();

    Result<String, String> result = ClaimCheck.consumeClaim(nonExistentId);

    assertThat(result.isFailure()).isTrue();
  }

  // ── Metadata operations ──────────────────────────────────────────────

  @Test
  void checkedMessagePreservesMetadata() {
    String payload = "Document";
    Map<String, String> metadata = Map.of(
        "filename", "report.pdf",
        "size-mb", "50");

    ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck(payload, metadata);

    assertThat(checked.metadata()).containsEntry("filename", "report.pdf")
        .containsEntry("size-mb", "50");
  }

  @Test
  void checkedMessageRetrieveWorks() {
    String payload = "Document";
    ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck(payload);

    Result<String, String> result = checked.retrieve();

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(s -> s, e -> null)).isEqualTo(payload);
  }

  // ── Type safety and generics ─────────────────────────────────────────

  @Test
  void claimCheckHandlesGenericTypes() {
    record Person(String name, int age) {}
    Person person = new Person("Alice", 30);

    UUID claimId = ClaimCheck.claimCheck(person).claimId();
    Result<Person, String> result = ClaimCheck.retrieve(claimId);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(p -> p.name(), e -> null)).isEqualTo("Alice");
  }

  // ── Error handling and validation ────────────────────────────────────

  @Test
  void claimCheckRejectsNullPayload() {
    assertThatThrownBy(() -> ClaimCheck.claimCheck(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload must not be null");
  }

  @Test
  void retrieveRejectsNullClaimId() {
    Result<String, String> result = ClaimCheck.retrieve(null);

    assertThat(result.isFailure()).isTrue();
    assertThat(result.fold(s -> null, e -> e)).contains("claimId must not be null");
  }

  @Test
  void consumeRejectsNullClaimId() {
    Result<String, String> result = ClaimCheck.consumeClaim(null);

    assertThat(result.isFailure()).isTrue();
    assertThat(result.fold(s -> null, e -> e)).contains("claimId must not be null");
  }

  // ── Concurrency and storage ──────────────────────────────────────────

  @Test
  void claimCountReflectsStorageSize() {
    ClaimCheck.claimCheck("Doc1");
    ClaimCheck.claimCheck("Doc2");
    ClaimCheck.claimCheck("Doc3");

    assertThat(ClaimCheck.claimCount()).isEqualTo(3);
  }

  @Test
  void clearAllRemovesAllClaims() {
    ClaimCheck.claimCheck("Doc1");
    ClaimCheck.claimCheck("Doc2");

    ClaimCheck.clearAll();

    assertThat(ClaimCheck.claimCount()).isEqualTo(0);
  }

  @Test
  void multipleClaimsAreIndependent() {
    String payload1 = "Document1";
    String payload2 = "Document2";

    UUID claimId1 = ClaimCheck.claimCheck(payload1).claimId();
    UUID claimId2 = ClaimCheck.claimCheck(payload2).claimId();

    Result<String, String> result1 = ClaimCheck.retrieve(claimId1);
    Result<String, String> result2 = ClaimCheck.retrieve(claimId2);

    assertThat(result1.fold(s -> s, e -> null)).isEqualTo(payload1);
    assertThat(result2.fold(s -> s, e -> null)).isEqualTo(payload2);
  }

  // ── Railway-oriented composition ─────────────────────────────────────

  @Test
  void resultCompositionWorksWithClaimCheck() {
    String payload = "document-123";
    UUID claimId = ClaimCheck.claimCheck(payload).claimId();

    Result<String, String> result = ClaimCheck.retrieve(claimId)
        .map(s -> s.toUpperCase())
        .map(s -> s + "-processed");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(s -> s, e -> null)).isEqualTo("DOCUMENT-123-PROCESSED");
  }

  @Test
  void resultFlatMapChainHandlesErrors() {
    UUID nonExistentId = UUID.randomUUID();

    Result<String, String> result = ClaimCheck.retrieve(nonExistentId)
        .map(s -> s.toUpperCase())
        .map(s -> s + "-processed");

    assertThat(result.isFailure()).isTrue();
  }

  @Test
  void checkedMessageMetadataWithResult() {
    Map<String, String> metadata = Map.of("priority", "high", "version", "2");
    ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck("payload", metadata);

    Result<String, String> result = checked.retrieve()
        .map(p -> p + " (priority=" + checked.metadata().get("priority") + ")");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.fold(s -> s, e -> null)).contains("(priority=high)");
  }
}
