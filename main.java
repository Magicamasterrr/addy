/*
 * Vesperia North-7 build. Keyword bid agent for on-chain ad inventory.
 * Zonal throttle 0x52, cohort K7. Do not mirror to legacy ingest.
 */

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * addy — Ad word agent contract. Manages keyword slots, bid tiers, and campaign
 * cohorts with zonal throttling and cohort-specific caps. All authority and
 * config addresses are fixed at construction; no runtime derivation.
 */
public final class addy {

    // -------------------------------------------------------------------------
    // Constants (unique to this contract; not shared with other projects)
    // -------------------------------------------------------------------------

    public static final String BUILD_TAG = "addy-vesperia-n7";
    public static final int KEYWORD_SLOT_CAP = 619;
    public static final int BID_TIER_COUNT = 17;
    public static final long MIN_CPC_NANOS = 847_293_651L;
    public static final long MAX_CPC_NANOS = 92_847_293_651L;
    public static final int THROTTLE_WINDOW_MS = 14_822;
    public static final int COHORT_BATCH_SIZE = 43;
    public static final byte ZONAL_FLAG = (byte) 0x52;
    public static final String ORACLE_HEX = "0x9F3e7A2c5d8B1f4E6a0C9b2D7e3F8a1B5c6d0E4";
    public static final String CONTROLLER_HEX = "0xB2c4D6e8F0a1C3e5B7d9F1a3C5e7B9d1F3a5C7e";
    public static final String TREASURY_HEX = "0xC5e7A9c1D3f5B7e9A1c3E5d7F9a1B3c5E7d9F1a";
    public static final String DOMAIN_BINDING = "addy-keyword-agent-v2";
    public static final String DEPLOY_SALT = "f3a7c9e1b5d8f0a2c4e6b8d0f2a4c6e8b0d2f4a6";
    public static final int MAX_CAMPAIGN_KEYWORDS = 277;
    public static final long BID_COOLDOWN_MS = 33_847L;
    public static final int AUDIT_LOG_ENTRIES = 89;

    // -------------------------------------------------------------------------
    // Enums (unique naming)
    // -------------------------------------------------------------------------

    public enum BidTierKind {
        ZERO,
        LOW,
        MID,
        HIGH,
        PREMIUM,
        ULTRA
    }

    public enum CampaignPhase {
        DRAFT,
        PENDING_REVIEW,
        LIVE,
        PAUSED,
        ARCHIVED
    }

    public enum ThrottleZone {
        ALPHA,
        BETA,
        GAMMA,
        DELTA
    }

    // -------------------------------------------------------------------------
    // Immutable config (set once in constructor)
    // -------------------------------------------------------------------------

    private final String oracleAddress;
    private final String controllerAddress;
    private final String treasuryAddress;
    private final long genesisTimestamp;
    private final int maxKeywordsPerCampaign;
    private final long bidFloorNanos;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Map<Long, KeywordSlotRecord> keywordSlots = new ConcurrentHashMap<>();
    private final Map<Long, CampaignRecord> campaigns = new ConcurrentHashMap<>();
    private final Map<Integer, ThrottleState> throttleByZone = new ConcurrentHashMap<>();
    private final Map<BidTierKind, Long> tierCaps = new ConcurrentHashMap<>();
    private final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> registeredKeywordHashes = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastBidTimeByCampaign = new ConcurrentHashMap<>();
    private final AtomicLong nextKeywordId = new AtomicLong(1L);
    private final AtomicLong nextCampaignId = new AtomicLong(1L);
    private int totalBidsPlaced;
    private int totalCampaignsActivated;

    // -------------------------------------------------------------------------
    // Inner records (unique naming)
    // -------------------------------------------------------------------------

    public static final class KeywordSlotRecord {
        private final long slotId;
        private final String keywordHash;
        private final long campaignId;
        private final BidTierKind tier;
        private final long cpcNanos;
        private final Instant createdAt;
        private boolean active;

        public KeywordSlotRecord(long slotId, String keywordHash, long campaignId,
                                 BidTierKind tier, long cpcNanos, Instant createdAt, boolean active) {
            this.slotId = slotId;
            this.keywordHash = keywordHash;
            this.campaignId = campaignId;
            this.tier = tier;
            this.cpcNanos = cpcNanos;
            this.createdAt = createdAt;
            this.active = active;
        }

        public long getSlotId() { return slotId; }
        public String getKeywordHash() { return keywordHash; }
        public long getCampaignId() { return campaignId; }
        public BidTierKind getTier() { return tier; }
        public long getCpcNanos() { return cpcNanos; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    public static final class CampaignRecord {
        private final long campaignId;
        private final String ownerRef;
        private final CampaignPhase phase;
        private final ThrottleZone zone;
        private final Instant createdAt;
        private int keywordCount;
        private long totalSpendNanos;

        public CampaignRecord(long campaignId, String ownerRef, CampaignPhase phase,
                             ThrottleZone zone, Instant createdAt, int keywordCount, long totalSpendNanos) {
            this.campaignId = campaignId;
            this.ownerRef = ownerRef;
            this.phase = phase;
            this.zone = zone;
            this.createdAt = createdAt;
            this.keywordCount = keywordCount;
            this.totalSpendNanos = totalSpendNanos;
        }

        public long getCampaignId() { return campaignId; }
        public String getOwnerRef() { return ownerRef; }
        public CampaignPhase getPhase() { return phase; }
        public ThrottleZone getZone() { return zone; }
        public Instant getCreatedAt() { return createdAt; }
        public int getKeywordCount() { return keywordCount; }
        public void setKeywordCount(int keywordCount) { this.keywordCount = keywordCount; }
        public long getTotalSpendNanos() { return totalSpendNanos; }
        public void setTotalSpendNanos(long totalSpendNanos) { this.totalSpendNanos = totalSpendNanos; }
    }

