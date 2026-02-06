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
