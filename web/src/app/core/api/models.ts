// API types are generated from the backend's OpenAPI document (web/openapi.json) into ./generated:
//   npm run api:update   (API running) refreshes the document and regenerates; npm run api:generate regenerates only.
// The names below keep the app's vocabulary (Address, BookingDetail, ...) as aliases of the generated models.
// Only frontend-only types (unions, generics, form models) are still written by hand here.
import type {
  AddressDto,
  AddressRequest,
  AddressView,
  AdminCategoryDto,
  AdminCouponDto,
  AdminCityDto,
  AdminGenieDetailDto,
  AdminPincodeDto,
  AdminServiceDto,
  AreaDto,
  AttachmentDto,
  AuthResponse,
  AvailabilityDto,
  Blocker,
  BookingDetailDto,
  BookingPhotoDto,
  BookingSummaryDto,
  CategoryDetailDto,
  CategoryDto,
  CategoryRequest,
  CategoryStat,
  ChallengeDto,
  CityDto,
  CityRequest,
  CompanyDto,
  CouponOfferDto,
  CouponRequest as CouponRequestDto,
  ConsentDto,
  ContactMessageDto,
  ContactRequest,
  CreateBookingRequest,
  DayCountDto,
  DaySlotsDto,
  DayStat,
  DeletionRequestRow,
  DeletionStatus,
  DraftRequest,
  ExportJobDto,
  FileLink,
  GenieCardDto,
  GenieDetailDto,
  GenieServiceDto,
  GenieStat,
  GenieStatsDto,
  HistoryDto,
  KycDocumentDto,
  LedgerLineDto,
  LegalDocumentDto,
  LocationCheckDto,
  LoginRequest,
  MyProfileDto,
  MyServiceDto,
  NotificationDto,
  OtpVerifyResponse,
  OutboxMessageDto,
  OverviewDto,
  PartyDto,
  PaymentDto,
  PaymentOrderDto,
  PayoutDto,
  PincodeAtDto,
  PincodeCheckDto,
  PincodeLocationDto,
  PincodeRequest,
  PreferenceDto,
  PriceBreakdown,
  ProfileRequest,
  QueueItemDto,
  RaiseRequest,
  ReceiptDto,
  RefundDto,
  RefundRequest,
  RegisterRequest,
  ResolveRequest,
  ReviewDto,
  SearchHitDto,
  ServiceDto,
  ServiceRequest,
  Slot,
  StartCodeDto,
  TicketDetail,
  TicketMessageDto,
  TicketSummary,
  TimeOffDto,
  InstantOfferDto,
  DemandCell,
  DemandMapDto,
  InstantQuoteDto,
  InstantRequestDto,
  TrackingDto,
  UserDto,
  UserRowDto,
  RedemptionDto,
  VerificationEventDto,
  VersionDto,
  WalletDto,
} from './generated/models';
export type { AddressRequest, AddressView, AuthResponse, CategoryRequest, CategoryStat, CityRequest, ContactRequest, CreateBookingRequest, DayStat, DeletionRequestRow, DeletionStatus, FileLink, GenieStat, LoginRequest, OtpVerifyResponse, PincodeRequest, PriceBreakdown, ProfileRequest, RefundRequest, RegisterRequest, ServiceRequest, Slot, TicketDetail, TicketSummary } from './generated/models';

/**
 * App names for the API models (aliases of the generated ones above), plus the frontend-only types: string
 * unions for statuses and codes, the generic Page<T>, and request shapes the API doesn't describe.
 * Money is a number in INR; instants are ISO-8601 UTC strings; dates are "yyyy-MM-dd".
 */

export type Role = 'CUSTOMER' | 'GENIE' | 'ADMIN';

export type User = UserDto;




export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

// ---------------------------------------------------------------- catalog

/**
 * A short-lived download link: an S3 presigned URL (cloud) or a signed /api/v1/files/... path (local).
 * It works without the login token, so the browser can open it directly; it stops working at expiresAt.
 */

/** A live city. lat/lng is its centre (the map opens there); radiusKm is how far from it ProGenie serves. */
export type City = CityDto;

/** A served PIN code of a city, e.g. 380009 · Navrangpura. */
export type Area = AreaDto;

export type PincodeCheck = PincodeCheckDto;

export type LocationCheck = LocationCheckDto;

/** Where a PIN code is (its rough centre), for moving the map pin there. */
export type PincodeLocation = PincodeLocationDto;

/** The PIN code at a map point (null when unknown there). */
export type PincodeAt = PincodeAtDto;

export type Category = CategoryDto;

export type ServiceItem = ServiceDto;

export type CategoryDetail = CategoryDetailDto;

export type SearchHit = SearchHitDto;

// ---------------------------------------------------------------- genies (public)

export type GenieCard = GenieCardDto;

export type GenieService = GenieServiceDto;

export type Review = ReviewDto;

export type GenieDetail = GenieDetailDto;


export type DayCount = DayCountDto;


export type DaySlots = DaySlotsDto;

// ---------------------------------------------------------------- customer

export type Address = AddressDto;


// ---------------------------------------------------------------- bookings

export type BookingStatus = 'REQUESTED' | 'ACCEPTED' | 'ON_THE_WAY' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED';
export type PaymentMethod = 'CASH' | 'ONLINE';
export type PaymentStatus = 'UNPAID' | 'PAID' | 'PARTIALLY_REFUNDED' | 'REFUNDED' | 'WAIVED';
export type FeeStatus = 'DUE' | 'PAID' | 'WAIVED';

export type BookingAction =
  | 'CANCEL'
  | 'RESCHEDULE'
  | 'CHANGE_TIP'
  | 'START_CODE'
  | 'PAY'
  | 'PAY_FEE'
  | 'REVIEW'
  | 'ACCEPT'
  | 'DECLINE'
  | 'START'
  | 'COMPLETE'
  | 'START_TRAVEL'
  | 'SHARE_LOCATION'
  | 'TRACK';

export type Party = PartyDto;


export type HistoryItem = HistoryDto;

export type BookingDetail = BookingDetailDto;
/** Genie Now (Phase 3C-9): price range before searching, the search itself, and an offer as a Genie sees it. */
export type InstantQuote = InstantQuoteDto;
export type InstantRequest = InstantRequestDto;
export type InstantOffer = InstantOfferDto;
/** Admin demand heatmap (Phase 3D). */
export type DemandMap = DemandMapDto;
export type { DemandCell };
export type DemandLayer = 'BOOKINGS' | 'UNMET';

export type InstantStatus = 'SEARCHING' | 'BOOKED' | 'NO_GENIE' | 'CANCELLED';

/** Live "on the way" map: the Genie's latest position, distance and arrival estimate (Phase 3C-10). */
export type Tracking = TrackingDto;
/** A before/after job photo with a short-lived link (Phase 3A-8). */
export type BookingPhoto = BookingPhotoDto;

export type BookingSummary = BookingSummaryDto;


export type StartCode = StartCodeDto;

// ---------------------------------------------------------------- payments

export type PaymentOrder = PaymentOrderDto;

export type Payment = PaymentDto;

export type Wallet = WalletDto;

export type LedgerLine = LedgerLineDto;

export type Payout = PayoutDto;

// ---------------------------------------------------------------- genie self-service

export type VerificationStatus = 'REGISTERED' | 'UNDER_REVIEW' | 'NEEDS_CHANGES' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
export type OnboardingStep = 'PROFILE' | 'LOCATION' | 'SERVICES' | 'AVAILABILITY' | 'ID_DOCUMENT' | 'SELFIE' | 'PAYOUT';
export type DocType = 'AADHAAR' | 'PAN' | 'SELFIE' | 'CERTIFICATE' | 'OTHER';

export type MyService = MyServiceDto;

export type AvailabilityWindow = AvailabilityDto;

export type GenieDocument = KycDocumentDto;

export type MyProfile = MyProfileDto;


export type TimeOff = TimeOffDto;


export type GenieStats = GenieStatsDto;

export type AppNotification = NotificationDto;

// ---------------------------------------------------------------- admin

export type QueueItem = QueueItemDto;

export type VerificationEvent = VerificationEventDto;

export type AdminGenieDetail = AdminGenieDetailDto;

export type Decision = 'APPROVE' | 'NEEDS_CHANGES' | 'REJECT' | 'SUSPEND' | 'REINSTATE';

export type UserRow = UserRowDto;

export type AdminCategory = AdminCategoryDto;


export type AdminService = AdminServiceDto;


/** A city as admins see it: live or not, centre and radius, PIN code count and pricing. */
export type AdminCity = AdminCityDto;


export type AdminPincode = AdminPincodeDto;


export type PricingRequest = Pick<AdminCity, 'commissionRate' | 'travelFreeKm' | 'travelBaseFee' | 'travelPerKm' | 'travelFeeCap' | 'instantFee'>;

export type ContactMessage = ContactMessageDto;



export type Overview = OverviewDto;

// ---------------------------------------------------------------- accounts: one-time codes

export type OtpPurpose = 'LOGIN' | 'RESET_PASSWORD' | 'VERIFY_PHONE' | 'VERIFY_EMAIL';

export type OtpChallenge = ChallengeDto;


// ---------------------------------------------------------------- legal and privacy

export type LegalKind = 'TERMS' | 'PRIVACY' | 'REFUNDS' | 'GENIE_AGREEMENT';

export type LegalDocument = LegalDocumentDto;

export type LegalVersion = VersionDto;

export type CompanyInfo = CompanyDto;

export type Consent = ConsentDto;

export type LegalDraftRequest = DraftRequest;

export type MessageCategory = 'BOOKING' | 'PAYMENT' | 'SUPPORT' | 'ACCOUNT' | 'PROMOTIONS';

export type NotificationPreference = PreferenceDto;

export type ExportJob = ExportJobDto;

export type DeletionBlocker = Blocker;



// ---------------------------------------------------------------- receipts and refunds

export type Receipt = ReceiptDto;

export type RefundLiability = 'GENIE' | 'PLATFORM' | 'SPLIT';


export type Refund = RefundDto;

// ---------------------------------------------------------------- complaints

export type TicketCategory = 'SAFETY' | 'DAMAGE' | 'NO_SHOW' | 'QUALITY' | 'PAYMENT' | 'BEHAVIOUR' | 'PRIVACY' | 'OTHER';
export type TicketStatus = 'OPEN' | 'IN_REVIEW' | 'AWAITING_REPLY' | 'RESOLVED' | 'CLOSED' | 'REJECTED';
export type TicketPriority = 'URGENT' | 'HIGH' | 'NORMAL';
export type TicketAction = 'REFUND' | 'REDO' | 'STRIKE' | 'NO_ACTION';


export type TicketMessage = TicketMessageDto;

export type TicketAttachment = AttachmentDto;


export type RaiseTicketRequest = RaiseRequest;

export type ResolveTicketRequest = ResolveRequest;

// ---------------------------------------------------------------- outgoing messages (admin)

export type OutboundMessage = OutboxMessageDto;

// ---------------------------------------------------------------- misc


/** RFC 9457 error body returned by the API. */
export interface ProblemDetail {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  errors?: Record<string, string>;
  /** The request's ID (also in the X-Request-Id header): quote it when reporting a problem. */
  errorId?: string;
  /** Extra fields some errors carry. */
  retryAfterSeconds?: number;
  attemptsLeft?: number;
  pendingConsents?: LegalKind[];
  blockers?: DeletionBlocker[];
  refundable?: number;
}

export type GenieSort = 'RATING' | 'PRICE' | 'EXPERIENCE';

// --- coupons (Phase 3B)
/** A coupon at checkout, with the discount it gives on this order or why it can't be used. */
export type CouponOffer = CouponOfferDto;
export type AdminCoupon = AdminCouponDto;
export type CouponRequest = CouponRequestDto;
export type CouponRedemption = RedemptionDto;
