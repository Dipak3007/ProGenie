package com.progenie.shared.config;

import com.progenie.shared.util.QueryUtil;

/**
 * Keys of the SQL statements in {@code src/main/resources/sql_scripts.properties}. Pass one to
 * {@link QueryUtil#get(String)}: {@code jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_LIST))}.
 *
 * <p>Each constant's value is its key; the name is the key in UPPER_SNAKE_CASE. {@code FRAGMENT_*} keys are pieces of
 * SQL used as slot values in {@link QueryUtil#get(String, java.util.Map)}. The {@code jpql} group is used by Spring
 * Data repositories through {@code @Query(name = ...)}. {@code QueryUtilTest} checks that this class and the file agree.
 */
public final class QueryConstants {

    private QueryConstants() {
    }

    // ------------------------------------------------------------------ Shared: job locks, rate limits, reusable fragments

    public static final String LOCK_JOB_ADVISORY = "lock.job.advisory";
    public static final String FRAGMENT_NONE = "fragment.none";
    public static final String UPSERT_RATE_LIMIT_HIT = "upsert.rate-limit.hit";
    public static final String DELETE_RATE_LIMIT_KEY = "delete.rate-limit.key";
    public static final String DELETE_RATE_LIMIT_OLD_WINDOWS = "delete.rate-limit.old-windows";

    // ------------------------------------------------------------------ Analytics (admin overview, Genie stats)

    public static final String GET_ANALYTICS_BOOKINGS_BY_STATUS = "get.analytics.bookings-by-status";
    public static final String GET_ANALYTICS_DAILY_SERIES = "get.analytics.daily-series";
    public static final String GET_ANALYTICS_GENIE_EARNINGS = "get.analytics.genie-earnings";
    public static final String GET_ANALYTICS_GENIE_JOB_COUNTS = "get.analytics.genie-job-counts";
    public static final String GET_ANALYTICS_GENIE_RATING = "get.analytics.genie-rating";
    public static final String GET_ANALYTICS_MONEY_TOTALS = "get.analytics.money-totals";
    public static final String GET_ANALYTICS_PEOPLE_TOTALS = "get.analytics.people-totals";
    public static final String GET_ANALYTICS_RESPONSE_STATS = "get.analytics.response-stats";
    public static final String GET_ANALYTICS_TOP_CATEGORIES = "get.analytics.top-categories";
    public static final String GET_ANALYTICS_TOP_GENIES = "get.analytics.top-genies";

    // ------------------------------------------------------------------ Bookings

    public static final String FRAGMENT_BOOKING_COUNTERPART_BOTH = "fragment.booking.counterpart-both";
    public static final String FRAGMENT_BOOKING_COUNTERPART_CUSTOMER = "fragment.booking.counterpart-customer";
    public static final String FRAGMENT_BOOKING_COUNTERPART_GENIE = "fragment.booking.counterpart-genie";
    public static final String FRAGMENT_BOOKING_ORDER_LATEST = "fragment.booking.order-latest";
    public static final String FRAGMENT_BOOKING_ORDER_SOONEST = "fragment.booking.order-soonest";
    public static final String FRAGMENT_BOOKING_OWNER_CUSTOMER = "fragment.booking.owner-customer";
    public static final String FRAGMENT_BOOKING_OWNER_GENIE = "fragment.booking.owner-genie";
    public static final String FRAGMENT_BOOKING_SCOPE_ACTIVE = "fragment.booking.scope-active";
    public static final String FRAGMENT_BOOKING_SCOPE_ALL = "fragment.booking.scope-all";
    public static final String FRAGMENT_BOOKING_SCOPE_PAST = "fragment.booking.scope-past";
    public static final String FRAGMENT_BOOKING_SCOPE_REQUESTS = "fragment.booking.scope-requests";
    public static final String FRAGMENT_BOOKING_SCOPE_UPCOMING = "fragment.booking.scope-upcoming";
    public static final String GET_BOOKING_ACTIVE_SLOTS = "get.booking.active-slots";
    public static final String GET_BOOKING_ADMIN_SEARCH = "get.booking.admin-search";
    public static final String GET_BOOKING_DETAIL = "get.booking.detail";
    public static final String GET_BOOKING_LIST = "get.booking.list";
    public static final String GET_BOOKING_NEXT_REF = "get.booking.next-ref";
    public static final String GET_BOOKING_REVIEWABLE = "get.booking.reviewable";
    public static final String GET_BOOKING_STATUS_HISTORY = "get.booking.status-history";
    public static final String COUNT_BOOKING_ADMIN_SEARCH = "count.booking.admin-search";
    public static final String COUNT_BOOKING_LIST = "count.booking.list";
    public static final String COUNT_BOOKING_RECENT_GENIE_CANCELLATIONS = "count.booking.recent-genie-cancellations";
    public static final String EXISTS_BOOKING_OUTSTANDING_FEE = "exists.booking.outstanding-fee";
    public static final String LOCK_BOOKING_DUE_REMINDERS = "lock.booking.due-reminders";
    public static final String LOCK_BOOKING_EXPIRED_REQUESTS = "lock.booking.expired-requests";
    public static final String INSERT_BOOKING_STATUS_HISTORY = "insert.booking.status-history";
    public static final String UPDATE_BOOKING_CLEAR_REMINDER = "update.booking.clear-reminder";
    public static final String UPDATE_BOOKING_COPY_ADDRESS_FROM_ORIGINAL = "update.booking.copy-address-from-original";
    public static final String UPDATE_BOOKING_REMINDER_SENT = "update.booking.reminder-sent";
    public static final String UPDATE_BOOKING_SNAPSHOT_ADDRESS = "update.booking.snapshot-address";
    public static final String LOCK_BOOKING_PHOTO_CONTEXT = "lock.booking.photo-context";
    public static final String GET_BOOKING_PHOTOS = "get.booking.photos";
    public static final String GET_BOOKING_PHOTO = "get.booking.photo";
    public static final String COUNT_BOOKING_PHOTOS_STAGE = "count.booking.photos-stage";
    public static final String INSERT_BOOKING_PHOTO = "insert.booking.photo";
    public static final String DELETE_BOOKING_PHOTO = "delete.booking.photo";
    public static final String GET_BOOKING_EXPIRED_PHOTOS = "get.booking.expired-photos";
    public static final String GET_BOOKING_TRACKING = "get.booking.tracking";
    public static final String UPSERT_BOOKING_TRACKING = "upsert.booking.tracking";
    public static final String DELETE_BOOKING_TRACKING = "delete.booking.tracking";
    public static final String DELETE_BOOKING_STALE_TRACKING = "delete.booking.stale-tracking";

    // ------------------------------------------------------------------ Catalog and city pricing

    public static final String GET_CATALOG_ACTIVE_CITIES = "get.catalog.active-cities";
    public static final String GET_CATALOG_ACTIVE_CITY_BY_SLUG = "get.catalog.active-city-by-slug";
    public static final String GET_CATALOG_ADMIN_CITIES = "get.catalog.admin-cities";
    public static final String GET_CATALOG_ADMIN_CATEGORIES = "get.catalog.admin-categories";
    public static final String GET_CATALOG_ADMIN_SERVICE = "get.catalog.admin-service";
    public static final String GET_CATALOG_ADMIN_SERVICES = "get.catalog.admin-services";
    public static final String GET_CATALOG_CATEGORIES = "get.catalog.categories";
    public static final String GET_CATALOG_CATEGORY_BY_SLUG = "get.catalog.category-by-slug";
    public static final String GET_CATALOG_CATEGORY_SERVICES = "get.catalog.category-services";
    public static final String GET_CATALOG_CITY_AREAS = "get.catalog.city-areas";
    public static final String GET_CATALOG_CITY_PINCODES = "get.catalog.city-pincodes";
    public static final String GET_CATALOG_CITY_READINESS = "get.catalog.city-readiness";
    public static final String GET_CATALOG_PINCODE_OWNER = "get.catalog.pincode-owner";
    public static final String GET_CATALOG_SEARCH_SERVICES = "get.catalog.search-services";
    public static final String COUNT_CATALOG_ACTIVE_CITIES = "count.catalog.active-cities";
    public static final String EXISTS_CATALOG_CATEGORY = "exists.catalog.category";
    public static final String EXISTS_CATALOG_CITY = "exists.catalog.city";
    public static final String INSERT_CATALOG_CATEGORY = "insert.catalog.category";
    public static final String INSERT_CATALOG_CITY = "insert.catalog.city";
    public static final String INSERT_CATALOG_CITY_DEFAULT_PRICING = "insert.catalog.city-default-pricing";
    public static final String INSERT_CATALOG_SERVICE = "insert.catalog.service";
    public static final String UPSERT_CATALOG_CITY_PINCODE = "upsert.catalog.city-pincode";
    public static final String UPSERT_CATALOG_CITY_PRICING = "upsert.catalog.city-pricing";
    public static final String UPDATE_CATALOG_CATEGORY = "update.catalog.category";
    public static final String UPDATE_CATALOG_CITY = "update.catalog.city";
    public static final String UPDATE_CATALOG_CITY_ACTIVE = "update.catalog.city-active";
    public static final String UPDATE_CATALOG_SERVICE = "update.catalog.service";
    public static final String DELETE_CATALOG_CITY_PINCODE = "delete.catalog.city-pincode";

    // ------------------------------------------------------------------ Serviceability: PIN codes, India boundary, live cities

    public static final String GET_SERVICEABILITY_PINCODE = "get.serviceability.pincode";
    public static final String GET_SERVICEABILITY_LIVE_CITY_AT = "get.serviceability.live-city-at";
    public static final String EXISTS_SERVICEABILITY_IN_INDIA = "exists.serviceability.in-india";
    public static final String EXISTS_SERVICEABILITY_WITHIN_CITY = "exists.serviceability.within-city";
    public static final String GET_SERVICEABILITY_NEAREST_PINCODE = "get.serviceability.nearest-pincode";

    // ------------------------------------------------------------------ Customer addresses and favourites

    public static final String GET_ADDRESS_LIST = "get.address.list";
    public static final String GET_ADDRESS_OWNED = "get.address.owned";
    public static final String COUNT_ADDRESS_BY_USER = "count.address.by-user";
    public static final String INSERT_ADDRESS_CREATE = "insert.address.create";
    public static final String UPDATE_ADDRESS_CLEAR_DEFAULT = "update.address.clear-default";
    public static final String UPDATE_ADDRESS_DETAILS = "update.address.details";
    public static final String UPDATE_ADDRESS_PROMOTE_OLDEST_DEFAULT = "update.address.promote-oldest-default";
    public static final String UPDATE_ADDRESS_SET_DEFAULT = "update.address.set-default";
    public static final String DELETE_ADDRESS_OWNED = "delete.address.owned";
    public static final String GET_FAVOURITE_GENIE_IDS = "get.favourite.genie-ids";
    public static final String INSERT_FAVOURITE_ADD = "insert.favourite.add";
    public static final String DELETE_FAVOURITE_REMOVE = "delete.favourite.remove";

    // ------------------------------------------------------------------ Accounts, sign-in, OTP, privacy (export and deletion), retention

    public static final String GET_DELETION_ADMIN_LIST = "get.deletion.admin-list";
    public static final String GET_DELETION_EXPORT_FILE_KEYS = "get.deletion.export-file-keys";
    public static final String GET_DELETION_GENIE_BALANCE = "get.deletion.genie-balance";
    public static final String GET_DELETION_SCHEDULED = "get.deletion.scheduled";
    public static final String GET_DELETION_SCHEDULED_FOR = "get.deletion.scheduled-for";
    public static final String COUNT_DELETION_ADMIN_LIST = "count.deletion.admin-list";
    public static final String COUNT_DELETION_OPEN_TICKETS = "count.deletion.open-tickets";
    public static final String COUNT_DELETION_PENDING_PAYOUTS = "count.deletion.pending-payouts";
    public static final String COUNT_DELETION_UNPAID = "count.deletion.unpaid";
    public static final String COUNT_DELETION_UPCOMING_BOOKINGS = "count.deletion.upcoming-bookings";
    public static final String EXISTS_DELETION_SCHEDULED = "exists.deletion.scheduled";
    public static final String LOCK_DELETION_DUE = "lock.deletion.due";
    public static final String INSERT_DELETION_REQUEST = "insert.deletion.request";
    public static final String UPDATE_DELETION_BLOCKED = "update.deletion.blocked";
    public static final String UPDATE_DELETION_CANCEL = "update.deletion.cancel";
    public static final String UPDATE_DELETION_DONE = "update.deletion.done";
    public static final String UPDATE_DELETION_REDACT_MESSAGES = "update.deletion.redact-messages";
    public static final String UPDATE_DELETION_RETIRE_GENIE_PROFILE = "update.deletion.retire-genie-profile";
    public static final String UPDATE_DELETION_TRIM_BOOKING_ADDRESSES = "update.deletion.trim-booking-addresses";
    public static final String DELETE_DELETION_ADDRESSES = "delete.deletion.addresses";
    public static final String DELETE_DELETION_EXPORT_JOBS = "delete.deletion.export-jobs";
    public static final String DELETE_DELETION_FAVOURITES = "delete.deletion.favourites";
    public static final String DELETE_DELETION_GENIE_LOCATION = "delete.deletion.genie-location";
    public static final String DELETE_DELETION_NOTIFICATIONS = "delete.deletion.notifications";
    public static final String DELETE_DELETION_RESET_TOKENS = "delete.deletion.reset-tokens";
    public static final String FRAGMENT_EXPORT_ADDRESSES = "fragment.export.addresses";
    public static final String FRAGMENT_EXPORT_BOOKINGS = "fragment.export.bookings";
    public static final String FRAGMENT_EXPORT_CONSENTS = "fragment.export.consents";
    public static final String FRAGMENT_EXPORT_FAVOURITES = "fragment.export.favourites";
    public static final String FRAGMENT_EXPORT_GENIE_DOCUMENTS = "fragment.export.genie-documents";
    public static final String FRAGMENT_EXPORT_GENIE_EARNINGS_LEDGER = "fragment.export.genie-earnings-ledger";
    public static final String FRAGMENT_EXPORT_GENIE_JOBS = "fragment.export.genie-jobs";
    public static final String FRAGMENT_EXPORT_GENIE_PAYOUTS = "fragment.export.genie-payouts";
    public static final String FRAGMENT_EXPORT_GENIE_PROFILE = "fragment.export.genie-profile";
    public static final String FRAGMENT_EXPORT_GENIE_REVIEWS_RECEIVED = "fragment.export.genie-reviews-received";
    public static final String FRAGMENT_EXPORT_NOTIFICATION_PREFERENCES = "fragment.export.notification-preferences";
    public static final String FRAGMENT_EXPORT_NOTIFICATIONS = "fragment.export.notifications";
    public static final String FRAGMENT_EXPORT_PAYMENTS = "fragment.export.payments";
    public static final String FRAGMENT_EXPORT_PROFILE = "fragment.export.profile";
    public static final String FRAGMENT_EXPORT_RECEIPTS = "fragment.export.receipts";
    public static final String FRAGMENT_EXPORT_REVIEWS_WRITTEN = "fragment.export.reviews-written";
    public static final String FRAGMENT_EXPORT_TICKETS = "fragment.export.tickets";
    public static final String GET_EXPORT_AS_JSON_ARRAY = "get.export.as-json-array";
    public static final String GET_EXPORT_AS_JSON_OBJECT = "get.export.as-json-object";
    public static final String GET_EXPORT_DOWNLOAD = "get.export.download";
    public static final String GET_EXPORT_EXPIRED = "get.export.expired";
    public static final String GET_EXPORT_JOB = "get.export.job";
    public static final String GET_EXPORT_LIST = "get.export.list";
    public static final String GET_EXPORT_RECENT = "get.export.recent";
    public static final String LOCK_EXPORT_PENDING = "lock.export.pending";
    public static final String INSERT_EXPORT_REQUEST = "insert.export.request";
    public static final String UPDATE_EXPORT_EXPIRE = "update.export.expire";
    public static final String UPDATE_EXPORT_FAILED = "update.export.failed";
    public static final String UPDATE_EXPORT_READY = "update.export.ready";
    public static final String GET_OTP_LAST_SENT_AT = "get.otp.last-sent-at";
    public static final String LOCK_OTP_CHALLENGE = "lock.otp.challenge";
    public static final String INSERT_OTP_CHALLENGE = "insert.otp.challenge";
    public static final String UPDATE_OTP_ATTEMPTS = "update.otp.attempts";
    public static final String UPDATE_OTP_CONSUMED = "update.otp.consumed";
    public static final String UPDATE_OTP_EXPIRE_PREVIOUS = "update.otp.expire-previous";
    public static final String DELETE_OTP_FOR_USER = "delete.otp.for-user";
    public static final String LOCK_PASSWORD_RESET_TOKEN = "lock.password-reset.token";
    public static final String INSERT_PASSWORD_RESET_TOKEN = "insert.password-reset.token";
    public static final String UPDATE_PASSWORD_RESET_USED = "update.password-reset.used";
    public static final String DELETE_PASSWORD_RESET_FOR_USER = "delete.password-reset.for-user";
    public static final String GET_RETENTION_EXPIRED_KYC_FILES = "get.retention.expired-kyc-files";
    public static final String GET_RETENTION_OLD_CLOSED_TICKETS = "get.retention.old-closed-tickets";
    public static final String UPDATE_RETENTION_DETACH_REFUNDS = "update.retention.detach-refunds";
    public static final String UPDATE_RETENTION_DETACH_STRIKES = "update.retention.detach-strikes";
    public static final String DELETE_RETENTION_OLD_MESSAGES = "delete.retention.old-messages";
    public static final String DELETE_RETENTION_OLD_OTPS = "delete.retention.old-otps";
    public static final String DELETE_RETENTION_OLD_RESET_TOKENS = "delete.retention.old-reset-tokens";
    public static final String DELETE_RETENTION_OLD_WEBHOOK_EVENTS = "delete.retention.old-webhook-events";
    public static final String GET_TICKET_ATTACHMENT_FILE_KEYS = "get.ticket.attachment-file-keys";
    public static final String DELETE_TICKET_BY_ID = "delete.ticket.by-id";
    public static final String GET_USER_HAS_VERIFIED_EMAIL = "get.user.has-verified-email";
    public static final String GET_USER_PHONE_VERIFIED = "get.user.phone-verified";
    public static final String GET_USER_ROLE = "get.user.role";
    public static final String GET_USER_SEARCH = "get.user.search";
    public static final String COUNT_USER_SEARCH = "count.user.search";
    public static final String LOCK_USER_BY_ID = "lock.user.by-id";
    public static final String UPDATE_USER_PASSWORD_LOCKED_UNTIL = "update.user.password-locked-until";
    public static final String GET_DELETION_BOOKING_PHOTO_KEYS = "get.deletion.booking-photo-keys";
    public static final String DELETE_DELETION_BOOKING_PHOTOS = "delete.deletion.booking-photos";

    // ------------------------------------------------------------------ Legal documents and consents

    public static final String GET_CONSENT_HISTORY = "get.consent.history";
    public static final String GET_CONSENT_IS_PENDING = "get.consent.is-pending";
    public static final String INSERT_CONSENT_ACCEPT = "insert.consent.accept";
    public static final String GET_LEGAL_ADMIN_LIST = "get.legal.admin-list";
    public static final String GET_LEGAL_CURRENT = "get.legal.current";
    public static final String GET_LEGAL_DOCUMENT = "get.legal.document";
    public static final String GET_LEGAL_DRAFT = "get.legal.draft";
    public static final String GET_LEGAL_VERSION = "get.legal.version";
    public static final String GET_LEGAL_VERSIONS = "get.legal.versions";
    public static final String LOCK_LEGAL_DOCUMENT = "lock.legal.document";
    public static final String LOCK_LEGAL_DRAFT = "lock.legal.draft";
    public static final String INSERT_LEGAL_DRAFT = "insert.legal.draft";
    public static final String UPDATE_LEGAL_DRAFT = "update.legal.draft";
    public static final String UPDATE_LEGAL_PUBLISH = "update.legal.publish";
    public static final String DELETE_LEGAL_DRAFT = "delete.legal.draft";

    // ------------------------------------------------------------------ Notifications and outgoing messages

    public static final String GET_MESSAGE_BODY = "get.message.body";
    public static final String GET_MESSAGE_BOOKING_CONTEXT = "get.message.booking-context";
    public static final String GET_MESSAGE_LIST = "get.message.list";
    public static final String COUNT_MESSAGE_LIST = "count.message.list";
    public static final String LOCK_MESSAGE_STATUS = "lock.message.status";
    public static final String INSERT_MESSAGE_ENQUEUE = "insert.message.enqueue";
    public static final String UPDATE_MESSAGE_CLAIM_DUE = "update.message.claim-due";
    public static final String UPDATE_MESSAGE_DELIVERY_REPORT = "update.message.delivery-report";
    public static final String UPDATE_MESSAGE_FAILED = "update.message.failed";
    public static final String UPDATE_MESSAGE_RESCHEDULE = "update.message.reschedule";
    public static final String UPDATE_MESSAGE_RETRY = "update.message.retry";
    public static final String UPDATE_MESSAGE_SENT = "update.message.sent";
    public static final String FRAGMENT_NOTIFICATION_UNREAD_ONLY = "fragment.notification.unread-only";
    public static final String GET_NOTIFICATION_LIST = "get.notification.list";
    public static final String COUNT_NOTIFICATION_LIST = "count.notification.list";
    public static final String COUNT_NOTIFICATION_UNREAD = "count.notification.unread";
    public static final String INSERT_NOTIFICATION_CREATE = "insert.notification.create";
    public static final String INSERT_NOTIFICATION_FOR_ADMINS = "insert.notification.for-admins";
    public static final String UPDATE_NOTIFICATION_MARK_ALL_READ = "update.notification.mark-all-read";
    public static final String UPDATE_NOTIFICATION_MARK_READ = "update.notification.mark-read";
    public static final String GET_NOTIFICATION_PREFERENCE_ALL = "get.notification-preference.all";
    public static final String GET_NOTIFICATION_PREFERENCE_ONE = "get.notification-preference.one";
    public static final String UPSERT_NOTIFICATION_PREFERENCE_SAVE = "upsert.notification-preference.save";
    public static final String DELETE_NOTIFICATION_PREFERENCE_ALL = "delete.notification-preference.all";
    public static final String GET_USER_ACTIVE_ADMIN_IDS = "get.user.active-admin-ids";
    public static final String GET_USER_CONTACT = "get.user.contact";

    // ------------------------------------------------------------------ Payments, refunds, receipts, ledger, wallet, payouts

    public static final String UPSERT_DOCUMENT_SEQUENCE_NEXT = "upsert.document-sequence.next";
    public static final String INSERT_LEDGER_ENTRY = "insert.ledger.entry";
    public static final String GET_PAYMENT_ADMIN_LIST = "get.payment.admin-list";
    public static final String GET_PAYMENT_BY_ID = "get.payment.by-id";
    public static final String GET_PAYMENT_BY_PROVIDER_ORDER = "get.payment.by-provider-order";
    public static final String GET_PAYMENT_CUSTOMER_LIST = "get.payment.customer-list";
    public static final String GET_PAYMENT_PENDING_ORDER = "get.payment.pending-order";
    public static final String GET_PAYMENT_REWORK_PAYOUT_SOURCE = "get.payment.rework-payout-source";
    public static final String LOCK_PAYMENT_BY_ID = "lock.payment.by-id";
    public static final String INSERT_PAYMENT_CASH = "insert.payment.cash";
    public static final String INSERT_PAYMENT_ONLINE_ORDER = "insert.payment.online-order";
    public static final String INSERT_PAYMENT_TIP = "insert.payment.tip";
    public static final String INSERT_PAYMENT_WEBHOOK_EVENT = "insert.payment.webhook-event";
    public static final String UPDATE_PAYMENT_FAILED = "update.payment.failed";
    public static final String UPDATE_PAYMENT_SUCCEEDED = "update.payment.succeeded";
    public static final String GET_PAYOUT_BY_ID = "get.payout.by-id";
    public static final String GET_PAYOUT_GENIE_IDS = "get.payout.genie-ids";
    public static final String GET_PAYOUT_LIST = "get.payout.list";
    public static final String GET_PAYOUT_PENDING_TOTAL = "get.payout.pending-total";
    public static final String COUNT_PAYOUT_LIST = "count.payout.list";
    public static final String EXISTS_PAYOUT_GENIE = "exists.payout.genie";
    public static final String LOCK_PAYOUT_BY_ID = "lock.payout.by-id";
    public static final String INSERT_PAYOUT_CREATE = "insert.payout.create";
    public static final String UPDATE_PAYOUT_FAILED = "update.payout.failed";
    public static final String UPDATE_PAYOUT_PAID = "update.payout.paid";
    public static final String GET_RECEIPT_FOR_BOOKING = "get.receipt.for-booking";
    public static final String GET_RECEIPT_FOR_CUSTOMER = "get.receipt.for-customer";
    public static final String GET_RECEIPT_NUMBER_FOR_PAYMENT = "get.receipt.number-for-payment";
    public static final String GET_RECEIPT_PAYMENT_DETAILS = "get.receipt.payment-details";
    public static final String GET_RECEIPT_REFUND_DETAILS = "get.receipt.refund-details";
    public static final String GET_RECEIPT_ROW = "get.receipt.row";
    public static final String GET_RECEIPT_SOURCE = "get.receipt.source";
    public static final String EXISTS_RECEIPT_FOR_PAYMENT = "exists.receipt.for-payment";
    public static final String EXISTS_RECEIPT_FOR_REFUND = "exists.receipt.for-refund";
    public static final String INSERT_RECEIPT_CREDIT_NOTE = "insert.receipt.credit-note";
    public static final String INSERT_RECEIPT_PAYMENT = "insert.receipt.payment";
    public static final String UPDATE_RECEIPT_FILE_KEY = "update.receipt.file-key";
    public static final String GET_REFUND_BY_ID = "get.refund.by-id";
    public static final String GET_REFUND_BY_PROVIDER_ID = "get.refund.by-provider-id";
    public static final String GET_REFUND_COMMITTED_TOTAL = "get.refund.committed-total";
    public static final String GET_REFUND_FOR_BOOKING = "get.refund.for-booking";
    public static final String GET_REFUND_FOR_PROCESSING = "get.refund.for-processing";
    public static final String GET_REFUND_PAYMENT = "get.refund.payment";
    public static final String GET_REFUND_PROCESSED_TOTAL = "get.refund.processed-total";
    public static final String LOCK_REFUND_PAYMENT = "lock.refund.payment";
    public static final String INSERT_REFUND_CREATE = "insert.refund.create";
    public static final String UPDATE_REFUND_FAILED = "update.refund.failed";
    public static final String UPDATE_REFUND_PAYMENT_REFUNDED = "update.refund.payment-refunded";
    public static final String UPDATE_REFUND_PROCESSED = "update.refund.processed";
    public static final String UPDATE_REFUND_PROVIDER_ID = "update.refund.provider-id";
    public static final String GET_WALLET_BALANCE = "get.wallet.balance";
    public static final String GET_WALLET_EARNINGS_SINCE = "get.wallet.earnings-since";
    public static final String GET_WALLET_ENTRIES = "get.wallet.entries";
    public static final String GET_WALLET_LIFETIME_EARNINGS = "get.wallet.lifetime-earnings";
    public static final String GET_WALLET_PAYOUTS = "get.wallet.payouts";
    public static final String COUNT_WALLET_COMPLETED_JOBS_SINCE = "count.wallet.completed-jobs-since";
    public static final String COUNT_WALLET_ENTRIES = "count.wallet.entries";
    public static final String GET_STATEMENT_GENIE = "get.statement.genie";
    public static final String GET_STATEMENT_BALANCE_BEFORE = "get.statement.balance-before";
    public static final String GET_STATEMENT_JOBS = "get.statement.jobs";
    public static final String GET_STATEMENT_PAYOUTS = "get.statement.payouts";
    public static final String GET_STATEMENT_ENTRIES = "get.statement.entries";

    // ------------------------------------------------------------------ Coupons (promotion)

    public static final String FRAGMENT_COUPON_SELECT = "fragment.coupon.select";
    public static final String FRAGMENT_COUPON_ADMIN_SELECT = "fragment.coupon.admin-select";
    public static final String GET_COUPON_BY_CODE = "get.coupon.by-code";
    public static final String GET_COUPON_PUBLIC_ACTIVE = "get.coupon.public-active";
    public static final String GET_COUPON_CONTEXT = "get.coupon.context";
    public static final String GET_COUPON_SERVICE_CATEGORY = "get.coupon.service-category";
    public static final String GET_COUPON_ADMIN_LIST = "get.coupon.admin-list";
    public static final String GET_COUPON_ADMIN_BY_ID = "get.coupon.admin-by-id";
    public static final String GET_COUPON_REDEMPTIONS = "get.coupon.redemptions";
    public static final String COUNT_COUPON_REDEMPTIONS = "count.coupon.redemptions";
    public static final String COUNT_COUPON_USES = "count.coupon.uses";
    public static final String COUNT_COUPON_CUSTOMER_USES = "count.coupon.customer-uses";
    public static final String EXISTS_COUPON_CUSTOMER_BOOKING = "exists.coupon.customer-booking";
    public static final String EXISTS_COUPON_CODE = "exists.coupon.code";
    public static final String EXISTS_COUPON_CATEGORY = "exists.coupon.category";
    public static final String EXISTS_COUPON_SERVICE = "exists.coupon.service";
    public static final String EXISTS_COUPON_CITY = "exists.coupon.city";
    public static final String LOCK_COUPON_BY_CODE = "lock.coupon.by-code";
    public static final String INSERT_COUPON = "insert.coupon";
    public static final String INSERT_COUPON_REDEMPTION = "insert.coupon.redemption";
    public static final String UPDATE_COUPON = "update.coupon";
    public static final String UPDATE_COUPON_STATUS = "update.coupon.status";
    public static final String UPDATE_COUPON_RELEASE = "update.coupon.release";

    // ------------------------------------------------------------------ Pricing

    public static final String GET_PRICING_ESTIMATE = "get.pricing.estimate";

    // ------------------------------------------------------------------ Genies: directory, profile, documents, verification

    public static final String FRAGMENT_GENIE_FILTER_CITY = "fragment.genie.filter-city";
    public static final String FRAGMENT_GENIE_FILTER_CATEGORY = "fragment.genie.filter-category";
    public static final String FRAGMENT_GENIE_FILTER_SERVICE = "fragment.genie.filter-service";
    public static final String FRAGMENT_GENIE_ORDER_EXPERIENCE = "fragment.genie.order-experience";
    public static final String FRAGMENT_GENIE_ORDER_PRICE = "fragment.genie.order-price";
    public static final String FRAGMENT_GENIE_ORDER_RATING = "fragment.genie.order-rating";
    public static final String GET_GENIE_BOOKABLE_OFFER = "get.genie.bookable-offer";
    public static final String GET_GENIE_CARD = "get.genie.card";
    public static final String GET_GENIE_CARDS = "get.genie.cards";
    public static final String GET_GENIE_DETAIL_REVIEWS = "get.genie.detail-reviews";
    public static final String GET_GENIE_DETAIL_SERVICES = "get.genie.detail-services";
    public static final String GET_GENIE_LIST = "get.genie.list";
    public static final String GET_GENIE_SERVES_LOCATION = "get.genie.serves-location";
    public static final String GET_GENIE_TIME_OFF_BETWEEN = "get.genie.time-off-between";
    public static final String GET_GENIE_WEEKLY_AVAILABILITY = "get.genie.weekly-availability";
    public static final String EXISTS_GENIE_SERVES_CITY = "exists.genie.serves-city";
    public static final String EXISTS_GENIE_LISTED = "exists.genie.listed";
    public static final String GET_GENIE_DOCUMENT_FILE = "get.genie-document.file";
    public static final String GET_GENIE_DOCUMENT_LIST = "get.genie-document.list";
    public static final String GET_GENIE_DOCUMENT_OWNED = "get.genie-document.owned";
    public static final String INSERT_GENIE_DOCUMENT_UPLOAD = "insert.genie-document.upload";
    public static final String UPDATE_GENIE_DOCUMENT_APPROVE_PENDING = "update.genie-document.approve-pending";
    public static final String UPDATE_GENIE_DOCUMENT_REVIEW = "update.genie-document.review";
    public static final String DELETE_GENIE_DOCUMENT_BY_ID = "delete.genie-document.by-id";
    public static final String GET_GENIE_PROFILE_ACTIVE_SERVICES_BY_IDS = "get.genie-profile.active-services-by-ids";
    public static final String GET_GENIE_PROFILE_AVAILABILITY = "get.genie-profile.availability";
    public static final String GET_GENIE_PROFILE_ME = "get.genie-profile.me";
    public static final String GET_GENIE_PROFILE_SERVICES = "get.genie-profile.services";
    public static final String GET_GENIE_PROFILE_STATUS = "get.genie-profile.status";
    public static final String GET_GENIE_PROFILE_UPCOMING_TIME_OFF = "get.genie-profile.upcoming-time-off";
    public static final String EXISTS_GENIE_PROFILE_BY_ID = "exists.genie-profile.by-id";
    public static final String LOCK_GENIE_PROFILE_STATUS = "lock.genie-profile.status";
    public static final String INSERT_GENIE_PROFILE_AVAILABILITY = "insert.genie-profile.availability";
    public static final String INSERT_GENIE_PROFILE_REGISTER = "insert.genie-profile.register";
    public static final String INSERT_GENIE_PROFILE_SERVICE = "insert.genie-profile.service";
    public static final String INSERT_GENIE_PROFILE_SERVICE_AREAS = "insert.genie-profile.service-areas";
    public static final String INSERT_GENIE_PROFILE_TIME_OFF = "insert.genie-profile.time-off";
    public static final String UPSERT_GENIE_PROFILE_LOCATION = "upsert.genie-profile.location";
    public static final String UPDATE_GENIE_PROFILE_CANCELLATION = "update.genie-profile.cancellation";
    public static final String UPDATE_GENIE_PROFILE_CLEAR_FLAG = "update.genie-profile.clear-flag";
    public static final String UPDATE_GENIE_PROFILE_COMPLETED_JOB = "update.genie-profile.completed-job";
    public static final String UPDATE_GENIE_PROFILE_DECISION = "update.genie-profile.decision";
    public static final String UPDATE_GENIE_PROFILE_DETAILS = "update.genie-profile.details";
    public static final String UPDATE_GENIE_PROFILE_FLAG = "update.genie-profile.flag";
    public static final String UPDATE_GENIE_PROFILE_ONLINE = "update.genie-profile.online";
    public static final String UPDATE_GENIE_PROFILE_RATING = "update.genie-profile.rating";
    public static final String UPDATE_GENIE_PROFILE_SUBMIT = "update.genie-profile.submit";
    public static final String DELETE_GENIE_PROFILE_AVAILABILITY = "delete.genie-profile.availability";
    public static final String DELETE_GENIE_PROFILE_SERVICE_AREAS = "delete.genie-profile.service-areas";
    public static final String DELETE_GENIE_PROFILE_SERVICES = "delete.genie-profile.services";
    public static final String DELETE_GENIE_PROFILE_TIME_OFF = "delete.genie-profile.time-off";
    public static final String COUNT_GENIE_STRIKE_RECENT = "count.genie-strike.recent";
    public static final String INSERT_GENIE_STRIKE_ADD = "insert.genie-strike.add";
    public static final String GET_GENIE_VERIFICATION_EVENTS = "get.genie-verification.events";
    public static final String GET_GENIE_VERIFICATION_FLAGS = "get.genie-verification.flags";
    public static final String GET_GENIE_VERIFICATION_QUEUE = "get.genie-verification.queue";
    public static final String COUNT_GENIE_VERIFICATION_QUEUE = "count.genie-verification.queue";
    public static final String INSERT_GENIE_VERIFICATION_EVENT = "insert.genie-verification.event";
    public static final String INSERT_GENIE_VERIFICATION_FLAG_CLEARED = "insert.genie-verification.flag-cleared";
    public static final String INSERT_GENIE_VERIFICATION_REGISTERED = "insert.genie-verification.registered";
    public static final String INSERT_GENIE_VERIFICATION_SUBMITTED = "insert.genie-verification.submitted";
    public static final String GET_USER_FULL_NAME = "get.user.full-name";
    public static final String UPDATE_GENIE_PROFILE_BADGES = "update.genie-profile.badges";
    public static final String UPDATE_GENIE_PROFILE_CLEAR_BADGES = "update.genie-profile.clear-badges";
    public static final String EXISTS_GENIE_PROFILE_BADGES_COMPUTED = "exists.genie-profile.badges-computed";
    public static final String GET_GENIE_PROFILE_BADGES = "get.genie-profile.badges";

    // ------------------------------------------------------------------ Reviews

    public static final String GET_REVIEW_BY_ID = "get.review.by-id";
    public static final String GET_REVIEW_FOR_GENIE = "get.review.for-genie";
    public static final String COUNT_REVIEW_FOR_GENIE = "count.review.for-genie";
    public static final String EXISTS_REVIEW_OWNED = "exists.review.owned";
    public static final String INSERT_REVIEW_CREATE = "insert.review.create";
    public static final String UPDATE_REVIEW_REPLY = "update.review.reply";

    // ------------------------------------------------------------------ Complaints (tickets) and the contact inbox

    public static final String GET_CONTACT_MESSAGE_LIST = "get.contact-message.list";
    public static final String COUNT_CONTACT_MESSAGE_LIST = "count.contact-message.list";
    public static final String INSERT_CONTACT_MESSAGE_CREATE = "insert.contact-message.create";
    public static final String UPDATE_CONTACT_MESSAGE_STATUS = "update.contact-message.status";
    public static final String FRAGMENT_TICKET_OVERDUE = "fragment.ticket.overdue";
    public static final String FRAGMENT_TICKET_OVERDUE_ONLY = "fragment.ticket.overdue-only";
    public static final String GET_TICKET_ADMIN_QUEUE = "get.ticket.admin-queue";
    public static final String GET_TICKET_ATTACHMENT = "get.ticket.attachment";
    public static final String GET_TICKET_ATTACHMENTS = "get.ticket.attachments";
    public static final String GET_TICKET_BOOKING_PAYMENT = "get.ticket.booking-payment";
    public static final String GET_TICKET_BY_ID = "get.ticket.by-id";
    public static final String GET_TICKET_DETAIL = "get.ticket.detail";
    public static final String GET_TICKET_MESSAGES = "get.ticket.messages";
    public static final String GET_TICKET_MINE = "get.ticket.mine";
    public static final String GET_TICKET_NEXT_REF = "get.ticket.next-ref";
    public static final String COUNT_TICKET_ADMIN_QUEUE = "count.ticket.admin-queue";
    public static final String COUNT_TICKET_ATTACHMENTS = "count.ticket.attachments";
    public static final String COUNT_TICKET_MINE = "count.ticket.mine";
    public static final String EXISTS_TICKET_OPEN_FOR_BOOKING = "exists.ticket.open-for-booking";
    public static final String LOCK_TICKET_BOOKING_FOR_RAISE = "lock.ticket.booking-for-raise";
    public static final String LOCK_TICKET_BY_ID = "lock.ticket.by-id";
    public static final String INSERT_TICKET_ATTACHMENT = "insert.ticket.attachment";
    public static final String INSERT_TICKET_CREATE = "insert.ticket.create";
    public static final String INSERT_TICKET_MESSAGE = "insert.ticket.message";
    public static final String UPDATE_TICKET_ACCEPT = "update.ticket.accept";
    public static final String UPDATE_TICKET_ADMIN_REPLY = "update.ticket.admin-reply";
    public static final String UPDATE_TICKET_ASSIGN = "update.ticket.assign";
    public static final String UPDATE_TICKET_CLOSE_SETTLED = "update.ticket.close-settled";
    public static final String UPDATE_TICKET_REJECT = "update.ticket.reject";
    public static final String UPDATE_TICKET_REOPEN = "update.ticket.reopen";
    public static final String UPDATE_TICKET_RESOLVE = "update.ticket.resolve";
    public static final String UPDATE_TICKET_STATUS = "update.ticket.status";
    public static final String UPDATE_TICKET_TOUCH = "update.ticket.touch";
    public static final String EXISTS_USER_ACTIVE_ADMIN = "exists.user.active-admin";

    // ------------------------------------------------------------------ JPQL for Spring Data repositories (@Query(name = ...)): entity names, not tables

    public static final String LOCK_BOOKING_ENTITY = "lock.booking.entity";
    public static final String UPDATE_REFRESH_TOKEN_REVOKE_ALL_FOR_USER = "update.refresh-token.revoke-all-for-user";
    public static final String UPDATE_REFRESH_TOKEN_REVOKE_FAMILY = "update.refresh-token.revoke-family";
}
