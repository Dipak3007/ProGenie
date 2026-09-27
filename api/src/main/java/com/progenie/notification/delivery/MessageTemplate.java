package com.progenie.notification.delivery;

/**
 * Every message ProGenie sends outside the app, with its text per channel. Placeholders are
 * {@code {name}}; a missing value renders as empty text. SMS texts avoid the ₹ sign (it would force
 * a Unicode SMS, which costs double) and must match the DLT-registered template word for word
 * once MSG91 is live.
 *
 * <p>{@code mandatory} messages ignore the user's channel preferences (security codes, the Genie's
 * new-request alert, account notices). {@code sensitive} messages have their text redacted from the
 * outbox as soon as they are sent.
 */
public enum MessageTemplate {

    OTP(MessageCategory.ACCOUNT, true, true,
        "{code} is your ProGenie code to {purpose}. It expires in {minutes} minutes. Never share it with anyone.",
        "{code} is your ProGenie code to {purpose}. It expires in {minutes} minutes. Never share it with anyone.",
        "{code} is your ProGenie code",
        """
            Hi {name},

            Use this code to {purpose}:

            {code}

            It expires in {minutes} minutes and works once. If you didn't ask for it, ignore this email. Your account is safe."""),

    PASSWORD_CHANGED(MessageCategory.ACCOUNT, true, false,
        "Your ProGenie password was changed and you were signed out on all devices. Not you? Reset it at {link}",
        "Your ProGenie password was changed and you were signed out on all devices. Not you? Reset it at {link}",
        "Your ProGenie password was changed",
        """
            Hi {name},

            Your password was changed on {when} and you were signed out on every device.

            If this wasn't you, reset your password straight away and contact us."""),

    BOOKING_REQUEST_GENIE(MessageCategory.BOOKING, true, false,
        "New ProGenie request {ref}: {service} on {when} in {area}. Accept or decline within {minutes} min: {link}",
        "New booking request {ref}: *{service}* on {when} in {area}, ₹{amount}. Please accept or decline within {minutes} minutes: {link}",
        "New booking request {ref}",
        """
            Hi {name},

            {customer} requested {service} on {when} in {area} (₹{amount}).

            Please accept or decline within {minutes} minutes, or the request expires."""),

    BOOKING_ACCEPTED(MessageCategory.BOOKING, false, false,
        "ProGenie: {genie} accepted {ref} for {service} on {when}. Share your start code only when they arrive.",
        "Hi {name}, *{genie}* accepted your booking {ref} for {service} on {when}. Share your 4-digit start code only when they arrive. Details: {link}",
        "Booking confirmed: {service} on {when}",
        """
            Hi {name},

            {genie} accepted your booking {ref} for {service} on {when}.

            Share your 4-digit start code only when your Genie is at your door."""),

    BOOKING_DECLINED(MessageCategory.BOOKING, false, false,
        "ProGenie: {genie} can't take {ref} ({service}, {when}). Please pick another Genie: {link}",
        "Sorry {name}, {genie} can't take your booking {ref} ({service}, {when}).{reasonText} Please pick another Genie: {link}",
        "Please pick another Genie for {ref}",
        """
            Hi {name},

            {genie} can't take your booking {ref} for {service} on {when}.{reasonText}

            Please pick another Genie; you haven't been charged."""),

    BOOKING_EXPIRED(MessageCategory.BOOKING, false, false,
        "ProGenie: {genie} didn't respond to {ref} in time. Please pick another Genie: {link}",
        "Sorry {name}, {genie} didn't respond to your booking {ref} in time. Please pick another Genie: {link}",
        "No response for {ref}",
        """
            Hi {name},

            {genie} didn't respond to your booking {ref} for {service} in time.

            Please pick another Genie; you haven't been charged."""),

    BOOKING_REMINDER_CUSTOMER(MessageCategory.BOOKING, false, false,
        "Reminder: {genie} arrives for {service} ({ref}) at {time} today. Keep your start code ready.",
        "Reminder: *{genie}* arrives for {service} ({ref}) at {time} today. Keep your 4-digit start code ready. {link}",
        "Reminder: {service} at {time} today",
        """
            Hi {name},

            {genie} arrives for {service} ({ref}) at {time} today.

            Keep your 4-digit start code ready and share it only when they're at your door."""),

    BOOKING_REMINDER_GENIE(MessageCategory.BOOKING, false, false,
        "Reminder: {service} for {customer} ({ref}) at {time} today in {area}.",
        "Reminder: *{service}* for {customer} ({ref}) at {time} today in {area}. Directions and details: {link}",
        "Reminder: {service} at {time} today",
        """
            Hi {name},

            You have {service} for {customer} ({ref}) at {time} today in {area}."""),

    BOOKING_CANCELLED_CUSTOMER(MessageCategory.BOOKING, false, false,
        "ProGenie: your booking {ref} for {service} on {when} was cancelled{byText}.",
        "Hi {name}, your booking {ref} for {service} on {when} was cancelled{byText}.{reasonText} {feeText}",
        "Booking {ref} was cancelled",
        """
            Hi {name},

            Your booking {ref} for {service} on {when} was cancelled{byText}.{reasonText}

            {feeText}"""),

    BOOKING_CANCELLED_GENIE(MessageCategory.BOOKING, false, false,
        "ProGenie: booking {ref} ({service}, {when}) was cancelled{byText}.",
        "Booking {ref} ({service}, {when}) was cancelled{byText}.{feeText}",
        "Booking {ref} was cancelled",
        """
            Hi {name},

            Booking {ref} for {service} on {when} was cancelled{byText}.{feeText}"""),

    BOOKING_COMPLETED(MessageCategory.BOOKING, false, false,
        "ProGenie: {genie} completed {ref}. {payText}",
        "Job done! *{genie}* completed {service} ({ref}). {payText} Please rate your Genie: {link}",
        "Job done: {service} ({ref})",
        """
            Hi {name},

            {genie} completed {service} ({ref}). {payText}

            Please take a moment to rate your Genie."""),

    PAYMENT_RECEIPT(MessageCategory.PAYMENT, false, false,
        "ProGenie: payment of Rs {amount} received for {ref}. Receipt {number}.",
        "Payment of ₹{amount} received for {ref}. Receipt {number}: {link}",
        "Receipt {number} for {ref}",
        """
            Hi {name},

            Thank you for your payment of ₹{amount} for {service} ({ref}), paid by {method}.

            Your receipt {number} is attached."""),

    REFUND_PROCESSED(MessageCategory.PAYMENT, false, false,
        "ProGenie refunded Rs {amount} for {ref}. {refundText}",
        "We refunded ₹{amount} for {ref}. {refundText} Credit note {number}: {link}",
        "Refund of ₹{amount} for {ref}",
        """
            Hi {name},

            We refunded ₹{amount} for {service} ({ref}). {refundText}

            Credit note {number} is attached."""),

    GENIE_VERIFICATION(MessageCategory.ACCOUNT, true, false,
        "ProGenie: {headline} {detail}",
        "{headline} {detail} {link}",
        "{headline}",
        """
            Hi {name},

            {headline}

            {detail}"""),

    TICKET_UPDATE(MessageCategory.SUPPORT, false, false,
        "ProGenie {ticketRef}: {headline}",
        "{ticketRef}: {headline} {link}",
        "{ticketRef}: {headline}",
        """
            Hi {name},

            {detail}

            Reference: {ticketRef} (booking {ref})."""),

    TICKET_SAFETY_ALERT(MessageCategory.SUPPORT, true, false,
        "URGENT ProGenie safety report {ticketRef} on {ref}. First reply due within 1 hour: {link}",
        "*URGENT* safety report {ticketRef} on {ref}. First reply due within 1 hour: {link}",
        "URGENT safety report {ticketRef}",
        """
            A safety report was raised on booking {ref}: {subject}

            The first reply is due within 1 hour."""),

    PAYOUT_SENT(MessageCategory.PAYMENT, false, false,
        "ProGenie sent your weekly payout of Rs {amount} to your UPI ID (ref {reference}).",
        "We sent your weekly payout of ₹{amount} to your UPI ID (ref {reference}).",
        "Weekly payout of ₹{amount} sent",
        """
            Hi {name},

            We sent your weekly payout of ₹{amount} for {period} to your UPI ID. Reference: {reference}."""),

    DATA_EXPORT_READY(MessageCategory.ACCOUNT, true, false,
        "ProGenie: your data export is ready. Download it from Profile > Privacy before {expires}.",
        "Your ProGenie data export is ready. Download it before {expires}: {link}",
        "Your ProGenie data is ready to download",
        """
            Hi {name},

            The copy of your data you asked for is ready. Download it from Profile → Privacy while logged in.

            The link works until {expires}."""),

    ACCOUNT_DELETION_SCHEDULED(MessageCategory.ACCOUNT, true, false,
        "ProGenie: your account will be deleted on {date}. Log in before then to cancel.",
        "Your ProGenie account will be deleted on {date}. Log in before then if you want to keep it: {link}",
        "Your account will be deleted on {date}",
        """
            Hi {name},

            As you asked, your ProGenie account will be deleted on {date}.

            Changed your mind? Log in before then and choose Cancel deletion.""");

    private final MessageCategory category;
    private final boolean mandatory;
    private final boolean sensitive;
    private final String sms;
    private final String whatsapp;
    private final String emailSubject;
    private final String emailBody;

    MessageTemplate(MessageCategory category, boolean mandatory, boolean sensitive, String sms, String whatsapp,
                    String emailSubject, String emailBody) {
        this.category = category;
        this.mandatory = mandatory;
        this.sensitive = sensitive;
        this.sms = sms;
        this.whatsapp = whatsapp;
        this.emailSubject = emailSubject;
        this.emailBody = emailBody;
    }

    public MessageCategory category() {
        return category;
    }

    public boolean mandatory() {
        return mandatory;
    }

    public boolean sensitive() {
        return sensitive;
    }

    String text(Channel channel) {
        return switch (channel) {
            case SMS -> sms;
            case WHATSAPP -> whatsapp;
            case EMAIL -> emailBody;
        };
    }

    String emailSubject() {
        return emailSubject;
    }
}
