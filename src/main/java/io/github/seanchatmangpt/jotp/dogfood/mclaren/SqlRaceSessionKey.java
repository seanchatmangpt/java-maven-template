package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.UUID;

/**
 * Session identity — mirrors {@code MESL.SqlRace.Domain.SessionKey}.
 *
 * <p>In SQL Race, every session is identified by a {@code SessionKey} consisting of a GUID and a
 * human-readable session identifier string. The GUID is stable across restarts and file transfers;
 * the identifier is set by the engineer at session creation.
 *
 * <p>Real SQL Race C# original:
 *
 * <pre>{@code
 * var sessionKey = SessionKey.NewKey();           // generates a new GUID
 * var clientSession = sessionManager.CreateSession(
 *     connectionString, sessionKey, sessIdentifier, DateTime.Now, "Session");
 * }</pre>
 *
 * @param guid       UUID that uniquely identifies this session across databases and file copies
 * @param identifier human-readable session name set by the engineer
 *                   (e.g. {@code "Bahrain_FP2_Car1_2025-03-01"})
 */
public record SqlRaceSessionKey(String guid, String identifier) {

    /**
     * Compact constructor: validates both fields are non-blank.
     *
     * @throws IllegalArgumentException if either field is null or blank
     */
    public SqlRaceSessionKey {
        if (guid == null || guid.isBlank()) {
            throw new IllegalArgumentException("SessionKey GUID must not be blank");
        }
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SessionKey identifier must not be blank");
        }
    }

    /**
     * Factory: generate a new key with a random UUID.
     *
     * @param identifier human-readable session name
     * @return new session key
     */
    public static SqlRaceSessionKey newKey(String identifier) {
        return new SqlRaceSessionKey(UUID.randomUUID().toString(), identifier);
    }
}
