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

    public static final class ThrottleState {
        private final ThrottleZone zone;
        private long lastActionAt;
        private int actionsInWindow;

        public ThrottleState(ThrottleZone zone, long lastActionAt, int actionsInWindow) {
            this.zone = zone;
            this.lastActionAt = lastActionAt;
            this.actionsInWindow = actionsInWindow;
        }

        public ThrottleZone getZone() { return zone; }
        public long getLastActionAt() { return lastActionAt; }
        public void setLastActionAt(long lastActionAt) { this.lastActionAt = lastActionAt; }
        public int getActionsInWindow() { return actionsInWindow; }
        public void setActionsInWindow(int actionsInWindow) { this.actionsInWindow = actionsInWindow; }
    }

    public static final class AuditEntry {
        private final long timestamp;
        private final String actionCode;
        private final long subjectId;
        private final String detail;

        public AuditEntry(long timestamp, String actionCode, long subjectId, String detail) {
            this.timestamp = timestamp;
            this.actionCode = actionCode;
            this.subjectId = subjectId;
            this.detail = detail;
        }

        public long getTimestamp() { return timestamp; }
        public String getActionCode() { return actionCode; }
        public long getSubjectId() { return subjectId; }
        public String getDetail() { return detail; }
    }

    // -------------------------------------------------------------------------
    // Constructor — authority addresses and caps passed in; no derivation
    // -------------------------------------------------------------------------

    public addy(String oracleAddress, String controllerAddress, String treasuryAddress,
                int maxKeywordsPerCampaign, long bidFloorNanos) {
        if (oracleAddress == null || oracleAddress.length() < 10) {
            throw new IllegalArgumentException("addy: invalid oracle address");
        }
        if (controllerAddress == null || controllerAddress.length() < 10) {
            throw new IllegalArgumentException("addy: invalid controller address");
        }
        if (treasuryAddress == null || treasuryAddress.length() < 10) {
            throw new IllegalArgumentException("addy: invalid treasury address");
        }
        if (maxKeywordsPerCampaign <= 0 || maxKeywordsPerCampaign > MAX_CAMPAIGN_KEYWORDS) {
            throw new IllegalArgumentException("addy: max keywords out of range");
        }
        if (bidFloorNanos < MIN_CPC_NANOS || bidFloorNanos > MAX_CPC_NANOS) {
            throw new IllegalArgumentException("addy: bid floor out of range");
        }
        this.oracleAddress = oracleAddress;
        this.controllerAddress = controllerAddress;
        this.treasuryAddress = treasuryAddress;
        this.genesisTimestamp = System.currentTimeMillis();
        this.maxKeywordsPerCampaign = maxKeywordsPerCampaign;
        this.bidFloorNanos = bidFloorNanos;
        initThrottleZones();
        initTierCaps();
    }

    /**
     * Default constructor using built-in constants (for tests or single-node use).
     * All addresses and caps are populated; no placeholders.
     */
    public addy() {
        this(ORACLE_HEX, CONTROLLER_HEX, TREASURY_HEX, 127, 1_847_293_651L);
    }

    private void initThrottleZones() {
        for (ThrottleZone z : ThrottleZone.values()) {
            throttleByZone.put(z.ordinal(), new ThrottleState(z, 0L, 0));
        }
    }

    private void initTierCaps() {
        tierCaps.put(BidTierKind.ZERO, 0L);
        tierCaps.put(BidTierKind.LOW, 10_000_000L);
        tierCaps.put(BidTierKind.MID, 50_000_000L);
        tierCaps.put(BidTierKind.HIGH, 200_000_000L);
        tierCaps.put(BidTierKind.PREMIUM, 800_000_000L);
        tierCaps.put(BidTierKind.ULTRA, 2_000_000_000L);
    }

    // -------------------------------------------------------------------------
    // Getters for immutable config
    // -------------------------------------------------------------------------

    public String getOracleAddress() { return oracleAddress; }
    public String getControllerAddress() { return controllerAddress; }
    public String getTreasuryAddress() { return treasuryAddress; }
    public long getGenesisTimestamp() { return genesisTimestamp; }
    public int getMaxKeywordsPerCampaign() { return maxKeywordsPerCampaign; }
    public long getBidFloorNanos() { return bidFloorNanos; }

    // -------------------------------------------------------------------------
    // Keyword hash (deterministic; no external input beyond keyword text)
    // -------------------------------------------------------------------------

    public static String keywordHash(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("addy: keyword blank");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] input = (DOMAIN_BINDING + ":" + keyword.trim()).getBytes(StandardCharsets.UTF_8);
            byte[] digest = md.digest(input);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("addy: SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // Throttle check (zone-based rate limit)
    // -------------------------------------------------------------------------

    public boolean canPerformInZone(ThrottleZone zone) {
        ThrottleState state = throttleByZone.get(zone.ordinal());
        if (state == null) return false;
        long now = System.currentTimeMillis();
        if (now - state.getLastActionAt() >= THROTTLE_WINDOW_MS) {
            return true;
        }
        return state.getActionsInWindow() < COHORT_BATCH_SIZE;
    }

    private void consumeThrottle(ThrottleZone zone) {
        ThrottleState state = throttleByZone.get(zone.ordinal());
        if (state == null) return;
        long now = System.currentTimeMillis();
        if (now - state.getLastActionAt() >= THROTTLE_WINDOW_MS) {
            state.setActionsInWindow(0);
            state.setLastActionAt(now);
        }
        state.setActionsInWindow(state.getActionsInWindow() + 1);
    }

    // -------------------------------------------------------------------------
    // Campaign lifecycle
    // -------------------------------------------------------------------------

    public long createCampaign(String ownerRef, ThrottleZone zone) {
        if (ownerRef == null || ownerRef.isBlank()) {
            throw new IllegalArgumentException("addy: owner ref blank");
        }
        if (!canPerformInZone(zone)) {
            throw new IllegalStateException("addy: throttle limit exceeded for zone " + zone);
        }
        long id = nextCampaignId.getAndIncrement();
        CampaignRecord rec = new CampaignRecord(id, ownerRef, CampaignPhase.DRAFT, zone,
                Instant.now(), 0, 0L);
        campaigns.put(id, rec);
        consumeThrottle(zone);
        appendAudit("CAMPAIGN_CREATE", id, "owner=" + ownerRef + ",zone=" + zone);
        return id;
    }

    public void transitionCampaignPhase(long campaignId, CampaignPhase toPhase) {
        CampaignRecord rec = campaigns.get(campaignId);
        if (rec == null) {
            throw new IllegalArgumentException("addy: campaign not found " + campaignId);
        }
        CampaignPhase from = rec.getPhase();
        if (from == CampaignPhase.ARCHIVED) {
            throw new IllegalStateException("addy: cannot transition archived campaign");
        }
        if (toPhase == CampaignPhase.LIVE) {
            totalCampaignsActivated++;
        }
        appendAudit("CAMPAIGN_PHASE", campaignId, from + "->" + toPhase);
    }

    // -------------------------------------------------------------------------
    // Keyword slot allocation
    // -------------------------------------------------------------------------

    public long allocateKeywordSlot(String keyword, long campaignId, BidTierKind tier, long cpcNanos) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("addy: keyword blank");
        }
