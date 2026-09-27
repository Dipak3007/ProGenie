/**
 * API models, mirroring the Spring Boot DTOs (see /v3/api-docs or docs/API.md).
 * Money is a number in INR; instants are ISO-8601 UTC strings; dates are "yyyy-MM-dd".
 */

export type Role = 'CUSTOMER' | 'GENIE' | 'ADMIN';

export interface User {
  id: string;
  fullName: string;
  email: string | null;
  phone: string;
  role: Role;
  phoneVerified: boolean;
  emailVerified: boolean;
  /** Policies the user must (re-)accept before booking or accepting jobs. */
  pendingConsents: LegalKind[];
  /** Set while an account deletion is scheduled. */
  deletionScheduledFor: string | null;
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  user: User;
}

export interface RegisterRequest {
  fullName: string;
  email?: string | null;
  phone: string;
  password: string;
  role: 'CUSTOMER' | 'GENIE';
  acceptTerms: boolean;
  acceptGenieAgreement?: boolean;
  marketingOptIn?: boolean;
}

export interface LoginRequest {
  identifier: string;
  password: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

// ---------------------------------------------------------------- catalog

export interface City {
  id: number;
  name: string;
  state: string;
}

export interface Category {
  id: number;
  slug: string;
  name: string;
  description: string | null;
  icon: string | null;
  imageUrl: string | null;
  serviceCount: number;
  startingPrice: number | null;
}

export interface ServiceItem {
  id: number;
  slug: string;
  name: string;
  description: string | null;
  basePrice: number;
  durationMinutes: number;
}

export interface CategoryDetail {
  category: Category;
  services: ServiceItem[];
}

export interface SearchHit {
  serviceId: number;
  serviceSlug: string;
  serviceName: string;
  categorySlug: string;
  categoryName: string;
  basePrice: number;
  durationMinutes: number;
}

// ---------------------------------------------------------------- genies (public)

export interface GenieCard {
  id: string;
  fullName: string;
  bio: string | null;
  experienceYears: number;
  avgRating: number;
  ratingCount: number;
  completedJobs: number;
  baseArea: string | null;
  online: boolean;
  startingPrice: number;
  categories: string;
  categorySlug: string;
  coverImage: string | null;
}

export interface GenieService {
  serviceId: number;
  name: string;
  price: number;
  durationMinutes: number;
}

export interface Review {
  id?: string;
  bookingId?: string;
  bookingRef?: string;
  serviceName?: string;
  rating: number;
  comment: string | null;
  customerName: string;
  createdAt: string;
  genieReply?: string | null;
  repliedAt?: string | null;
}

export interface GenieDetail {
  genie: GenieCard;
  services: GenieService[];
  reviews: Review[];
}

export interface PriceBreakdown {
  serviceAmount: number;
  distanceKm: number;
  travelFee: number;
  tipAmount: number;
  totalAmount: number;
  commissionRate: number;
  commissionAmount: number;
  genieEarning: number;
}

export interface DayCount {
  date: string;
  available: number;
}

export interface Slot {
  start: string;
  end: string;
}

export interface DaySlots {
  date: string;
  genieId: string;
  serviceId: number;
  durationMinutes: number;
  slots: Slot[];
}

// ---------------------------------------------------------------- customer

export interface Address {
  id: string;
  label: string;
  line1: string;
  line2: string | null;
  landmark: string | null;
  area: string | null;
  cityId: number | null;
  cityName: string | null;
  pincode: string;
  lat: number;
  lng: number;
  isDefault: boolean;
}

export interface AddressRequest {
  label: string;
  line1: string;
  line2?: string | null;
  landmark?: string | null;
  area?: string | null;
  cityId?: number | null;
  pincode: string;
  lat: number;
  lng: number;
  isDefault?: boolean;
}

// ---------------------------------------------------------------- bookings

export type BookingStatus = 'REQUESTED' | 'ACCEPTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED';
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
  | 'COMPLETE';

export interface Party {
  id: string;
  name: string;
  phone: string | null;
  avgRating: number | null;
}

export interface AddressView {
  label: string | null;
  line1: string | null;
  line2: string | null;
  landmark: string | null;
  area: string | null;
  city: string | null;
  pincode: string | null;
}

export interface HistoryItem {
  fromStatus: string | null;
  toStatus: string;
  actorRole: string | null;
  reason: string | null;
  changedAt: string;
}

export interface BookingDetail {
  id: string;
  bookingRef: string;
  status: BookingStatus;
  serviceId: number;
  serviceName: string;
  categoryName: string;
  categorySlug: string;
  genie: Party;
  customer: Party;
  address: AddressView | null;
  slotStart: string;
  slotEnd: string;
  expiresAt: string | null;
  notes: string | null;
  serviceAmount: number;
  extraAmount: number;
  extraNote: string | null;
  distanceKm: number;
  travelFee: number;
  tipAmount: number;
  totalAmount: number;
  commissionAmount: number;
  geniePayout: number;
  paymentMethod: PaymentMethod;
  paymentStatus: PaymentStatus;
  cancellationFee: number | null;
  cancellationFeeStatus: FeeStatus | null;
  cancellationFeeIfCancelledNow: number | null;
  cancelReason: string | null;
  cancelledByRole: string | null;
  rescheduledCount: number;
  reviewed: boolean;
  createdAt: string;
  acceptedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  allowedActions: BookingAction[];
  history: HistoryItem[];
}

export interface BookingSummary {
  id: string;
  bookingRef: string;
  status: BookingStatus;
  serviceName: string;
  categorySlug: string;
  counterpartName: string;
  slotStart: string;
  slotEnd: string;
  totalAmount: number;
  paymentMethod: PaymentMethod;
  paymentStatus: PaymentStatus;
  area: string | null;
  cancellationFeeStatus: FeeStatus | null;
  reviewed: boolean;
}

export interface CreateBookingRequest {
  genieId: string;
  serviceId: number;
  addressId: string;
  slotStart: string;
  paymentMethod: PaymentMethod;
  tipAmount?: number;
  notes?: string | null;
}

export interface StartCode {
  code: string;
  message: string;
}

// ---------------------------------------------------------------- payments

export interface PaymentOrder {
  paymentId: string;
  provider: string;
  orderId: string;
  amount: number;
  currency: string;
  purpose: 'BOOKING' | 'CANCELLATION_FEE';
  bookingRef: string;
  /** Razorpay public key for checkout.js; null with the fake gateway. */
  keyId: string | null;
}

export interface Payment {
  id: string;
  purpose: string;
  method: string;
  provider: string | null;
  amount: number;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REFUNDED' | string;
  failureReason: string | null;
  createdAt: string;
  refundedAmount: number;
}

export interface Wallet {
  balance: number;
  pendingPayouts: number;
  availableForPayout: number;
  commissionDue: number;
  earningsThisWeek: number;
  jobsThisWeek: number;
  lifetimeEarnings: number;
  currency: string;
}

export interface LedgerLine {
  id: number;
  bookingRef: string | null;
  account: string;
  direction: 'D' | 'C';
  amount: number;
  description: string | null;
  createdAt: string;
}

export interface Payout {
  id: string;
  genieId: string;
  genieName: string;
  upiId: string | null;
  periodStart: string;
  periodEnd: string;
  amount: number;
  status: 'PENDING' | 'PAID' | 'FAILED' | string;
  reference: string | null;
  createdAt: string;
  paidAt: string | null;
}

// ---------------------------------------------------------------- genie self-service

export type VerificationStatus = 'REGISTERED' | 'UNDER_REVIEW' | 'NEEDS_CHANGES' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
export type OnboardingStep = 'PROFILE' | 'LOCATION' | 'SERVICES' | 'AVAILABILITY' | 'ID_DOCUMENT' | 'SELFIE' | 'PAYOUT';
export type DocType = 'AADHAAR' | 'PAN' | 'SELFIE' | 'CERTIFICATE' | 'OTHER';

export interface MyService {
  serviceId: number;
  name: string;
  categoryName: string;
  basePrice: number;
  priceOverride: number | null;
  price: number;
  durationMinutes: number;
}

export interface AvailabilityWindow {
  dayOfWeek: number; // 1 = Monday … 7 = Sunday
  startTime: string; // "09:00" or "09:00:00"
  endTime: string;
}

export interface GenieDocument {
  id: string;
  docType: DocType;
  maskedNumber: string | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string;
  fileName: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  reviewNote: string | null;
  createdAt: string;
}

export interface MyProfile {
  id: string;
  fullName: string;
  phone: string;
  email: string | null;
  bio: string | null;
  experienceYears: number | null;
  verificationStatus: VerificationStatus;
  verificationNote: string | null;
  baseArea: string | null;
  baseLat: number | null;
  baseLng: number | null;
  serviceRadiusKm: number | null;
  payoutUpiId: string | null;
  online: boolean;
  avgRating: number;
  ratingCount: number;
  completedJobs: number;
  cancellationCount: number;
  submittedAt: string | null;
  approvedAt: string | null;
  services: MyService[];
  availability: AvailabilityWindow[];
  documents: GenieDocument[];
  missingSteps: OnboardingStep[];
  canSubmit: boolean;
}

export interface ProfileRequest {
  bio?: string | null;
  experienceYears?: number | null;
  baseArea?: string | null;
  baseLat: number;
  baseLng: number;
  serviceRadiusKm?: number | null;
  payoutUpiId?: string | null;
}

export interface TimeOff {
  id: number;
  startsAt: string;
  endsAt: string;
  reason: string | null;
}

export interface DayStat {
  day: string;
  bookings: number;
  completed: number;
  gmv: number;
}

export interface GenieStats {
  from: string;
  to: string;
  requestsReceived: number;
  completed: number;
  cancelledByGenie: number;
  acceptanceRate: number | null;
  earnings: number;
  avgRating: number;
  ratingCount: number;
  daily: DayStat[];
}

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  body: string | null;
  link: string | null;
  readAt: string | null;
  createdAt: string;
}

// ---------------------------------------------------------------- admin

export interface QueueItem {
  id: string;
  fullName: string;
  phone: string;
  baseArea: string | null;
  verificationStatus: VerificationStatus;
  submittedAt: string | null;
  createdAt: string;
  serviceCount: number;
  pendingDocuments: number;
  flaggedAt: string | null;
  flagReason: string | null;
}

export interface VerificationEvent {
  fromStatus: string | null;
  toStatus: string;
  reason: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface AdminGenieDetail {
  profile: MyProfile;
  userStatus: string;
  flaggedAt: string | null;
  flagReason: string | null;
  history: VerificationEvent[];
}

export type Decision = 'APPROVE' | 'NEEDS_CHANGES' | 'REJECT' | 'SUSPEND' | 'REINSTATE';

export interface UserRow {
  id: string;
  fullName: string;
  email: string | null;
  phone: string;
  role: Role;
  status: 'ACTIVE' | 'SUSPENDED' | 'DELETED' | string;
  createdAt: string;
  bookingCount: number;
}

export interface AdminCategory {
  id: number;
  slug: string;
  name: string;
  description: string | null;
  icon: string | null;
  imageUrl: string | null;
  sortOrder: number;
  active: boolean;
  serviceCount: number;
}

export interface CategoryRequest {
  slug: string;
  name: string;
  description?: string | null;
  icon?: string | null;
  imageUrl?: string | null;
  sortOrder?: number | null;
  active?: boolean;
}

export interface AdminService {
  id: number;
  categoryId: number;
  categoryName: string;
  slug: string;
  name: string;
  description: string | null;
  basePrice: number;
  durationMinutes: number;
  active: boolean;
  genieCount: number;
}

export interface ServiceRequest {
  categoryId: number;
  slug: string;
  name: string;
  description?: string | null;
  basePrice: number;
  durationMinutes?: number | null;
  active?: boolean;
}

export interface CityPricing {
  cityId: number;
  name: string;
  state: string;
  active: boolean;
  commissionRate: number;
  travelFreeKm: number;
  travelBaseFee: number;
  travelPerKm: number;
  travelFeeCap: number;
  currency: string;
}

export type PricingRequest = Pick<CityPricing, 'commissionRate' | 'travelFreeKm' | 'travelBaseFee' | 'travelPerKm' | 'travelFeeCap'>;

export interface ContactMessage {
  id: number;
  name: string;
  email: string;
  phone: string | null;
  subject: string;
  message: string;
  status: 'NEW' | 'IN_PROGRESS' | 'RESOLVED' | string;
  createdAt: string;
  resolvedAt: string | null;
}

export interface CategoryStat {
  slug: string;
  name: string;
  completed: number;
  gmv: number;
}

export interface GenieStat {
  id: string;
  name: string;
  completed: number;
  avgRating: number;
  earnings: number;
}

export interface Overview {
  from: string;
  to: string;
  bookingsCreated: number;
  bookingsByStatus: Record<string, number>;
  completed: number;
  gmv: number;
  commission: number;
  avgOrderValue: number;
  acceptanceRate: number | null;
  cancellationRate: number | null;
  newCustomers: number;
  newGenies: number;
  approvedGenies: number;
  pendingVerifications: number;
  flaggedGenies: number;
  outstandingFees: number;
  topCategories: CategoryStat[];
  topGenies: GenieStat[];
  daily: DayStat[];
}

// ---------------------------------------------------------------- accounts: one-time codes

export type OtpPurpose = 'LOGIN' | 'RESET_PASSWORD' | 'VERIFY_PHONE' | 'VERIFY_EMAIL';

export interface OtpChallenge {
  challengeId: string;
  channel: 'SMS' | 'EMAIL';
  /** Masked, e.g. 98******05 */
  destination: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
}

export interface OtpVerifyResponse {
  purpose: OtpPurpose;
  accessToken: string | null;
  expiresIn: number | null;
  user: User | null;
  resetToken: string | null;
  resetTokenExpiresIn: number | null;
}

// ---------------------------------------------------------------- legal and privacy

export type LegalKind = 'TERMS' | 'PRIVACY' | 'REFUNDS' | 'GENIE_AGREEMENT';

export interface LegalDocument {
  id: string;
  kind: LegalKind;
  version: number;
  title: string;
  /** Markdown */
  body: string;
  changeSummary: string | null;
  requiresReacceptance: boolean;
  effectiveFrom: string | null;
  publishedAt: string | null;
  updatedAt: string;
}

export interface LegalVersion {
  version: number;
  title: string;
  changeSummary: string | null;
  requiresReacceptance: boolean;
  effectiveFrom: string | null;
  publishedAt: string;
}

export interface CompanyInfo {
  legalName: string | null;
  address: string | null;
  city: string | null;
  supportEmail: string | null;
  supportPhone: string | null;
  grievanceName: string | null;
  grievanceEmail: string | null;
}

export interface Consent {
  kind: LegalKind;
  version: number;
  title: string;
  acceptedAt: string;
}

export interface LegalDraftRequest {
  title: string;
  body: string;
  changeSummary: string | null;
  requiresReacceptance: boolean;
  effectiveFrom: string | null;
}

export type MessageCategory = 'BOOKING' | 'PAYMENT' | 'SUPPORT' | 'ACCOUNT' | 'PROMOTIONS';

export interface NotificationPreference {
  category: MessageCategory;
  sms: boolean;
  whatsapp: boolean;
  email: boolean;
}

export interface ExportJob {
  id: string;
  status: 'PENDING' | 'READY' | 'FAILED' | 'EXPIRED';
  sizeBytes: number | null;
  requestedAt: string;
  readyAt: string | null;
  expiresAt: string | null;
  error: string | null;
}

export interface DeletionBlocker {
  code: 'UPCOMING_BOOKINGS' | 'OPEN_TICKETS' | 'UNPAID' | 'WALLET_NOT_SETTLED' | 'PAYOUT_PENDING' | string;
  message: string;
}

export interface DeletionStatus {
  requestId: string | null;
  status: 'NONE' | 'SCHEDULED';
  requestedAt: string | null;
  scheduledFor: string | null;
  blockers: DeletionBlocker[];
}

export interface DeletionRequestRow {
  id: string;
  userId: string;
  fullName: string;
  role: Role;
  status: 'SCHEDULED' | 'CANCELLED' | 'DONE' | 'BLOCKED';
  reason: string | null;
  requestedAt: string;
  scheduledFor: string;
  completedAt: string | null;
  note: string | null;
}

// ---------------------------------------------------------------- receipts and refunds

export interface Receipt {
  id: string;
  number: string;
  kind: 'RECEIPT' | 'CREDIT_NOTE';
  bookingId: string;
  paymentId: string | null;
  refundId: string | null;
  amount: number;
  issuedAt: string;
}

export type RefundLiability = 'GENIE' | 'PLATFORM' | 'SPLIT';

export interface RefundRequest {
  amount: number | null;
  liability: RefundLiability;
  genieShare?: number | null;
  reason?: string | null;
  method?: 'GATEWAY' | 'MANUAL' | null;
  manualReference?: string | null;
}

export interface Refund {
  id: string;
  paymentId: string;
  bookingId: string;
  amount: number;
  genieShare: number;
  platformShare: number;
  reason: string;
  method: 'GATEWAY' | 'MANUAL';
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  providerRefundId: string | null;
  manualReference: string | null;
  failureReason: string | null;
  ticketId: string | null;
  createdAt: string;
  processedAt: string | null;
}

// ---------------------------------------------------------------- complaints

export type TicketCategory = 'SAFETY' | 'DAMAGE' | 'NO_SHOW' | 'QUALITY' | 'PAYMENT' | 'BEHAVIOUR' | 'PRIVACY' | 'OTHER';
export type TicketStatus = 'OPEN' | 'IN_REVIEW' | 'AWAITING_REPLY' | 'RESOLVED' | 'CLOSED' | 'REJECTED';
export type TicketPriority = 'URGENT' | 'HIGH' | 'NORMAL';
export type TicketAction = 'REFUND' | 'REDO' | 'STRIKE' | 'NO_ACTION';

export interface TicketSummary {
  id: string;
  ticketRef: string;
  type: 'COMPLAINT' | 'PRIVACY';
  bookingId: string | null;
  bookingRef: string | null;
  category: TicketCategory;
  priority: TicketPriority;
  status: TicketStatus;
  subject: string;
  raisedBy: string;
  raisedByName: string;
  raisedByRole: Role;
  assignedToName: string | null;
  firstResponseDue: string;
  resolutionDue: string;
  overdue: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TicketMessage {
  id: number;
  authorId: string | null;
  authorName: string;
  authorRole: Role | 'SYSTEM';
  body: string;
  internal: boolean;
  createdAt: string;
}

export interface TicketAttachment {
  id: string;
  fileName: string | null;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  createdAt: string;
}

export interface TicketDetail {
  id: string;
  ticketRef: string;
  type: 'COMPLAINT' | 'PRIVACY';
  bookingId: string | null;
  bookingRef: string | null;
  serviceName: string | null;
  customerId: string | null;
  customerName: string | null;
  genieId: string | null;
  genieName: string | null;
  category: TicketCategory;
  priority: TicketPriority;
  status: TicketStatus;
  subject: string;
  description: string;
  raisedBy: string;
  raisedByName: string;
  raisedByRole: Role;
  assignedTo: string | null;
  assignedToName: string | null;
  firstResponseDue: string;
  resolutionDue: string;
  overdue: boolean;
  firstRespondedAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  resolutionActions: string | null;
  resolutionNote: string | null;
  refundId: string | null;
  redoBookingId: string | null;
  redoBookingRef: string | null;
  createdAt: string;
  messages: TicketMessage[];
  attachments: TicketAttachment[];
  canReply: boolean;
  canAddPhotos: boolean;
  canAccept: boolean;
  canReopen: boolean;
}

export interface RaiseTicketRequest {
  category: TicketCategory;
  subject: string;
  description: string;
}

export interface ResolveTicketRequest {
  actions: TicketAction[];
  note: string;
  refund?: RefundRequest | null;
  paymentId?: string | null;
  redo?: { slotStart: string; genieId?: string | null } | null;
  strikeReason?: string | null;
}

// ---------------------------------------------------------------- outgoing messages (admin)

export interface OutboundMessage {
  id: string;
  channel: 'SMS' | 'WHATSAPP' | 'EMAIL';
  template: string;
  destination: string;
  subject: string | null;
  body: string;
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';
  attempts: number;
  provider: string | null;
  deliveryStatus: string | null;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
  deliveredAt: string | null;
}

// ---------------------------------------------------------------- misc

export interface ContactRequest {
  name: string;
  email: string;
  phone?: string | null;
  subject: string;
  message: string;
}

/** RFC 9457 error body returned by the API. */
export interface ProblemDetail {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  errors?: Record<string, string>;
  /** Extra fields some errors carry. */
  retryAfterSeconds?: number;
  attemptsLeft?: number;
  pendingConsents?: LegalKind[];
  blockers?: DeletionBlocker[];
  refundable?: number;
}

export type GenieSort = 'RATING' | 'PRICE' | 'EXPERIENCE';
