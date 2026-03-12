package io.github.seanchatmangpt.jotp.messaging.construction;

import java.util.Map;
import java.util.UUID;
import org.acme.Result;

/**
 * Real-world example: ClaimCheck pattern for large document transmission.
 *
 * <p>Scenario: A file processing system where large PDF documents are submitted for processing.
 * Instead of sending the 50MB PDF over the network, we store it locally, send a small token,
 * and the worker retrieves it on-demand. This saves bandwidth and enables deferred loading.
 *
 * <p>Process flow:
 * <ol>
 *   <li>Client submits large PDF; ClaimCheck stores it and returns token
 *   <li>Client sends lightweight CheckedMessage (token only) to process queue
 *   <li>Worker receives token, verifies claim exists, retrieves PDF
 *   <li>Worker processes PDF, then releases the claim (cleanup)
 *   <li>If worker fails, supervisor can retry with same token (idempotent)
 * </ol>
 */
public class ClaimCheckExample {

  /**
   * Domain model: a PDF document ready for processing.
   */
  record PdfDocument(String filename, byte[] content, String contentType) {
    PdfDocument {
      if (filename == null || filename.isBlank()) {
        throw new IllegalArgumentException("filename must not be blank");
      }
      if (content == null || content.length == 0) {
        throw new IllegalArgumentException("content must not be empty");
      }
    }

    int sizeInMB() {
      return content.length / (1024 * 1024);
    }
  }

  /**
   * Submit a large PDF for processing.
   *
   * <p>This would typically be called by an HTTP endpoint or queue listener.
   *
   * @param doc the PDF to process
   * @return CheckedMessage token to send to workers
   */
  public static ClaimCheck.CheckedMessage submitPdfForProcessing(PdfDocument doc) {
    // Store PDF in claim store, get lightweight token
    return ClaimCheck.claimCheck(
        doc,
        Map.of(
            "filename", doc.filename(),
            "content-type", doc.contentType(),
            "size-mb", String.valueOf(doc.sizeInMB()),
            "submitted-at", String.valueOf(System.currentTimeMillis())));
  }

  /**
   * Worker: retrieve and process a PDF via its claim token.
   *
   * <p>This is typically called by a background worker/process.
   *
   * @param checked the CheckedMessage token
   * @return Result.Ok with processing result, or Result.Err with error message
   */
  public static Result<ProcessingResult, String> processPdf(
      ClaimCheck.CheckedMessage checked) {

    String filename = checked.metadata().getOrDefault("filename", "unknown");

    // Retrieve PDF from claim store
    Result<PdfDocument, String> pdfResult = checked.retrieve();

    // Railway-oriented: if retrieve fails, processing fails
    return pdfResult.flatMap(pdf -> {
      try {
        // Simulate processing: extract text, run OCR, etc.
        String extractedText = simulateOcrExtraction(pdf);
        int pageCount = simulatePageCount(pdf);
        long processingTimeMs = System.currentTimeMillis();

        ProcessingResult result = new ProcessingResult(
            filename,
            pageCount,
            extractedText.length(),
            processingTimeMs,
            "success");

        return Result.ok(result);
      } catch (Exception e) {
        return Result.failure("PDF processing failed: " + e.getMessage());
      }
    });
  }

  /**
   * Clean up after processing: release the claim to free storage.
   *
   * @param claimId the token
   * @return true if released; false if already released or not found
   */
  public static boolean cleanupPdf(UUID claimId) {
    return ClaimCheck.release(claimId);
  }

  /**
   * Retry-safe pattern: check if a claim still exists before retrying.
   *
   * <p>If the claim was already consumed by another worker, this returns false.
   * Otherwise, the claim can be reprocessed idempotently.
   *
   * @param checked the CheckedMessage token
   * @return true if claim exists and can be retried; false if already consumed
   */
  public static boolean canRetryPdfProcessing(ClaimCheck.CheckedMessage checked) {
    return ClaimCheck.exists(checked.claimId());
  }

  /**
   * Single-use pattern: retrieve and immediately consume the claim.
   *
   * <p>Useful for exactly-once processing semantics.
   *
   * @param claimId the token
   * @return Result.Ok with PDF, or Result.Err if claim not found
   */
  public static Result<PdfDocument, String> consumePdfClaim(UUID claimId) {
    return ClaimCheck.consumeClaim(claimId);
  }

  // ── Simulation helpers ───────────────────────────────────────────────

  private static String simulateOcrExtraction(PdfDocument doc) {
    // In reality, this would call a PDF library (iText, Apache PDFBox) and OCR engine
    return "Extracted text: " + doc.filename() + " contains " + doc.content.length
        + " bytes";
  }

  private static int simulatePageCount(PdfDocument doc) {
    // Rough estimate: 5KB per page (varies widely)
    return Math.max(1, doc.content.length / 5000);
  }

  /**
   * Result of PDF processing.
   */
  record ProcessingResult(
      String filename,
      int pageCount,
      int extractedCharacters,
      long processingTimeMs,
      String status) {}

  private ClaimCheckExample() {
    // example class
  }
}
