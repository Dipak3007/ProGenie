#!/usr/bin/env python3
"""
End-to-end smoke test for progenie-api: drives every backend flow over HTTP, the way the Angular app will.

Uses only the Python standard library. Start the API with relaxed timing so the whole job lifecycle can
run in one go (otherwise a Genie can only start a job 30 minutes before its slot):

    cd api
    mvn spring-boot:run -Dspring-boot.run.arguments="--progenie.booking.min-lead-time=5m \
        --progenie.booking.start-early-window=120h --progenie.booking.free-cancellation-window=48h"

    python3 scripts/e2e_smoke_test.py            # API at http://localhost:8080
    API=http://localhost:8080 python3 scripts/e2e_smoke_test.py

The script creates new bookings, users and a category each run (unique names), so it can be re-run.
Exit code 0 = every check passed.
"""
import datetime as dt
import hashlib
import hmac
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("API", "http://localhost:8080").rstrip("/")
MAILPIT = os.environ.get("MAILPIT", "http://localhost:8025").rstrip("/")
WEBHOOK_SECRET = os.environ.get("WEBHOOK_SECRET", "local-dev-webhook-secret")
PASSWORD = "ProGenie@123"
RUN = uuid.uuid4().hex[:6]

passed, failed = 0, 0


def check(condition, label, detail=None):
    global passed, failed
    if condition:
        passed += 1
        print(f"  ✓ {label}")
    else:
        failed += 1
        print(f"  ✗ {label}" + (f"  ->  {detail}" if detail is not None else ""))
    return condition


def section(title):
    print(f"\n== {title}")


class Session:
    """One logged-in (or anonymous) user with its own cookie jar (the refresh cookie)."""

    def __init__(self, name):
        self.name = name
        self.token = None
        self.user = None
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, headers=None, raw=None, content_type=None):
        data = None
        hdrs = {"Accept": "application/json"}
        if raw is not None:
            data = raw
            hdrs["Content-Type"] = content_type or "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        if self.token:
            hdrs["Authorization"] = "Bearer " + self.token
        hdrs.update(headers or {})
        req = urllib.request.Request(BASE + path, data=data, method=method, headers=hdrs)
        try:
            with self.opener.open(req, timeout=30) as res:
                payload = res.read()
                ctype = res.headers.get("Content-Type", "")
                return res.status, (json.loads(payload) if payload and "json" in ctype else payload)
        except urllib.error.HTTPError as e:
            payload = e.read()
            try:
                return e.code, json.loads(payload)
            except ValueError:
                return e.code, payload

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body, **kw)

    def put(self, path, body=None, **kw):
        return self.call("PUT", path, body, **kw)

    def patch(self, path, body=None, **kw):
        return self.call("PATCH", path, body, **kw)

    def delete(self, path, **kw):
        return self.call("DELETE", path, **kw)

    def login(self, identifier, password=PASSWORD):
        status, body = self.post("/api/v1/auth/login", {"identifier": identifier, "password": password})
        if status == 200:
            self.token = body["accessToken"]
            self.user = body["user"]
        return status, body


def mailpit_messages(to, since=None):
    """Messages Mailpit holds for an address (SMS arrive as <phone>@sms.progenie.local), newest first."""
    q = urllib.parse.quote(f"to:{to}")
    with urllib.request.urlopen(f"{MAILPIT}/api/v1/search?query={q}", timeout=10) as res:
        msgs = json.loads(res.read())["messages"]
    return [m for m in msgs if since is None or m["ID"] not in since]


def mailpit_message(message_id):
    with urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{message_id}", timeout=10) as res:
        return json.loads(res.read())


def wait_for_message(to, since=(), timeout=25):
    """Waits until a new message for `to` arrives (the outbox dispatcher runs every few seconds)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        fresh = mailpit_messages(to, set(since))
        if fresh:
            return mailpit_message(fresh[0]["ID"])
        time.sleep(1)
    return None


def seen(to):
    return {m["ID"] for m in mailpit_messages(to)}


def sms_address(phone):
    return f"{phone[-10:]}@sms.progenie.local"


def otp_from(message):
    import re
    found = re.search(r"\b(\d{6})\b", (message or {}).get("Subject", "") + " " + (message or {}).get("Text", ""))
    return found.group(1) if found else None


def request_otp(session, identifier, purpose):
    """Asks for a code, waiting out the resend limit (30 s by default; the test API runs with 3 s)."""
    for _ in range(3):
        status, body = session.post("/api/v1/auth/otp/request", {"identifier": identifier, "purpose": purpose})
        if status == 429 and code_of(body) == "OTP_TOO_SOON":
            time.sleep(body.get("retryAfterSeconds", 3))
            continue
        return status, body
    return status, body


def register(session, full_name, phone, role="CUSTOMER", verify=True):
    """Signs up (accepting the policies) and, by default, verifies the phone with an SMS code from Mailpit."""
    body = {"fullName": full_name, "phone": phone, "password": PASSWORD, "role": role, "acceptTerms": True,
            "acceptGenieAgreement": role == "GENIE"}
    status, reg = session.post("/api/v1/auth/register", body)
    if status == 201:
        session.token, session.user = reg["accessToken"], reg["user"]
        if verify:
            before = seen(sms_address(phone))
            _, ch = request_otp(session, None, "VERIFY_PHONE")
            code = otp_from(wait_for_message(sms_address(phone), before))
            session.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": code})
    return status, reg


def code_of(body):
    return body.get("code") if isinstance(body, dict) else None


def multipart(fields, file_field, filename, content, content_type):
    boundary = "----pg" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; filename=\"{filename}\"\r\n"
                 f"Content-Type: {content_type}\r\n\r\n".encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def free_slots(anon, genie_id, service_id, needed=1, skip=()):
    """Returns up to `needed` free slot start times (ISO strings), soonest first."""
    status, days = anon.get(f"/api/v1/genies/{genie_id}/slot-days?serviceId={service_id}&days=14")
    found = []
    for day in days if status == 200 else []:
        if day["available"] == 0:
            continue
        _, d = anon.get(f"/api/v1/genies/{genie_id}/slots?serviceId={service_id}&date={day['date']}")
        for s in d["slots"]:
            if s["start"] not in skip:
                found.append(s["start"])
            if len(found) >= needed:
                return found
    return found


def main():
    anon = Session("anon")
    customer, genie, admin = Session("customer"), Session("genie"), Session("admin")

    # ------------------------------------------------------------------ public + auth
    section("Public catalog, search and auth")
    s, cats = anon.get("/api/v1/categories")
    check(s == 200 and len(cats) >= 10, "categories are public", s)
    s, hits = anon.get("/api/v1/search?q=fan")
    check(s == 200 and any("Fan" in h["serviceName"] for h in hits), "search finds a fan service", hits)
    s, _ = anon.get("/api/v1/bookings")
    check(s == 401, "bookings need login (401)", s)
    for sess, ident in ((customer, "customer@example.com"), (genie, "genie@example.com"), (admin, "admin@progenie.in")):
        s, body = sess.login(ident)
        check(s == 200, f"login {ident}", body)
    s, _ = customer.get("/api/v1/genie/profile")
    check(s == 403, "a customer cannot call Genie endpoints (403)", s)
    s, _ = genie.get("/api/v1/admin/genies")
    check(s == 403, "a Genie cannot call admin endpoints (403)", s)

    # ------------------------------------------------------------------ account
    section("Account: profile and password")
    s, me = customer.patch("/api/v1/me", {"fullName": customer.user["fullName"], "email": "customer@example.com"})
    check(s == 200 and me["email"] == "customer@example.com", "update own profile", me)
    s, body = customer.post("/api/v1/auth/change-password", {"currentPassword": "wrong-password", "newPassword": "Another@123"})
    check(s == 400 and code_of(body) == "WRONG_PASSWORD", "wrong current password is rejected", body)
    s, _ = customer.post("/api/v1/auth/change-password", {"currentPassword": PASSWORD, "newPassword": "Temp@12345"})
    s2, _ = Session("x").login("customer@example.com", "Temp@12345")
    customer.post("/api/v1/auth/change-password", {"currentPassword": "Temp@12345", "newPassword": PASSWORD})
    check(s == 204 and s2 == 200, "change password, log in with it, change it back", (s, s2))
    s, _ = customer.post("/api/v1/auth/refresh")
    check(s == 200, "current login survives the password change (refresh works)", s)

    # ------------------------------------------------------------------ genie + address
    section("Genie profile, service and customer address")
    s, gp = genie.get("/api/v1/genie/profile")
    check(s == 200 and gp["verificationStatus"] == "APPROVED", "approved Genie reads own profile", gp.get("verificationStatus"))
    genie_id = gp["id"]
    service = gp["services"][0]
    service_id = service["serviceId"]
    s, _ = genie.post("/api/v1/genie/status", {"online": True})
    check(s == 200, "approved Genie can go online", s)
    s, addr = customer.post("/api/v1/me/addresses", {
        "label": f"Test {RUN}", "line1": "12 Test Society", "area": gp["baseArea"] or "Navrangpura",
        "pincode": "380009", "lat": gp["baseLat"] + 0.01, "lng": gp["baseLng"] + 0.01, "isDefault": False})
    check(s == 201, "customer adds an address near the Genie", addr)
    address_id = addr["id"]
    s, lst = customer.get("/api/v1/me/addresses")
    check(s == 200 and any(a["id"] == address_id for a in lst), "address is listed", s)
    s, _ = customer.put(f"/api/v1/me/favourites/{genie_id}")
    s2, favs = customer.get("/api/v1/me/favourites")
    check(s == 204 and any(f["id"] == genie_id for f in favs), "favourite a Genie", (s, len(favs) if isinstance(favs, list) else favs))
    s, detail = anon.get(f"/api/v1/genies/{genie_id}")
    check(s == 200 and len(detail["services"]) > 0, "public Genie profile", s)

    slots = free_slots(anon, genie_id, service_id, needed=12)
    check(len(slots) >= 8, f"found free slots ({len(slots)})", slots[:3])
    if len(slots) < 8:
        return

    def book(slot, method="CASH", key=None, tip=0, who=customer, address=None):
        headers = {"Idempotency-Key": key} if key else {}
        return who.post("/api/v1/bookings", {"genieId": genie_id, "serviceId": service_id,
                                             "addressId": address or address_id, "slotStart": slot,
                                             "paymentMethod": method, "tipAmount": tip,
                                             "notes": "Smoke test " + RUN}, headers=headers)

    # ------------------------------------------------------------------ booking 1: cash, full lifecycle
    section("Booking 1: cash job, full lifecycle")
    key = "smoke-" + RUN
    s, b1 = book(slots[0], "CASH", key, tip=20)
    check(s == 201 and b1["status"] == "REQUESTED", "customer books (REQUESTED)", b1)
    s, again = book(slots[0], "CASH", key, tip=20)
    check(s == 201 and again["id"] == b1["id"], "same Idempotency-Key returns the same booking", again.get("id"))
    other = Session("other")
    phone = "9" + str(int(time.time() * 1000))[-9:]
    s, _ = register(other, f"Other Customer {RUN}", phone)
    s, oaddr = other.post("/api/v1/me/addresses", {"label": "Home", "line1": "1 Other St", "area": "Paldi",
                                                   "pincode": "380007", "lat": gp["baseLat"], "lng": gp["baseLng"],
                                                   "isDefault": True})
    s, clash = book(slots[0], who=other, address=oaddr["id"])
    check(s == 409 and code_of(clash) == "SLOT_TAKEN", "another customer cannot take the same slot (409)", clash)
    s, gview = genie.get(f"/api/v1/genie/bookings/{b1['id']}")
    check(s == 200 and gview["address"]["line1"] is None and gview["customer"]["phone"] is None,
          "before accepting, the Genie sees no street address or phone", gview.get("address"))
    check("ACCEPT" in gview["allowedActions"], "Genie can ACCEPT", gview["allowedActions"])
    s, reqs = genie.get("/api/v1/genie/bookings?scope=REQUESTS")
    check(s == 200 and any(i["id"] == b1["id"] for i in reqs["items"]), "request is in the Genie's REQUESTS tab", s)
    s, acc = genie.post(f"/api/v1/genie/bookings/{b1['id']}/accept")
    check(s == 200 and acc["status"] == "ACCEPTED" and acc["address"]["line1"], "Genie accepts and now sees the address", acc.get("status"))
    s, cview = customer.get(f"/api/v1/bookings/{b1['id']}")
    check(cview["genie"]["phone"] is not None and cview["commissionAmount"] is None,
          "customer sees the Genie's phone but not the commission", cview.get("genie"))
    s, body = genie.post(f"/api/v1/genie/bookings/{b1['id']}/start", {"code": "0000"})
    check(s in (400, 422), "starting needs the customer's code", body)
    s, sc = customer.post(f"/api/v1/bookings/{b1['id']}/start-code")
    check(s == 200 and len(sc["code"]) == 4, "customer gets a start code", sc)
    wrong = "0000" if sc["code"] != "0000" else "1111"
    s, body = genie.post(f"/api/v1/genie/bookings/{b1['id']}/start", {"code": wrong})
    check(s == 400 and code_of(body) == "WRONG_START_CODE", "wrong code is rejected", body)
    s, st = genie.post(f"/api/v1/genie/bookings/{b1['id']}/start", {"code": sc["code"]})
    check(s == 200 and st["status"] == "IN_PROGRESS", "right code starts the job", st.get("status") if isinstance(st, dict) else st)
    s, body = genie.post(f"/api/v1/genie/bookings/{b1['id']}/complete", {"extraAmount": 150, "cashCollected": True})
    check(s == 400 and code_of(body) == "EXTRA_NOTE_REQUIRED", "extra charge needs a note", body)
    s, s_wallet_before = genie.get("/api/v1/genie/wallet")
    s, done = genie.post(f"/api/v1/genie/bookings/{b1['id']}/complete",
                         {"extraAmount": 150, "extraNote": "New switch", "cashCollected": True})
    check(s == 200 and done["status"] == "COMPLETED" and done["paymentStatus"] == "PAID", "complete with cash -> PAID", done)
    expected_total = round(float(b1["serviceAmount"]) + float(b1["travelFee"]) + 20 + 150, 2)
    check(abs(float(done["totalAmount"]) - expected_total) < 0.01, f"total includes extra and tip ({expected_total})", done["totalAmount"])
    s, w = genie.get("/api/v1/genie/wallet")
    delta = round(float(w["balance"]) - float(s_wallet_before["balance"]), 2)
    check(abs(delta + float(done["commissionAmount"])) < 0.01,
          "cash job: Genie wallet goes down by the commission they now owe", (delta, done["commissionAmount"]))

    section("Reviews")
    s, rv = customer.post(f"/api/v1/bookings/{b1['id']}/review", {"rating": 5, "comment": "Quick and tidy " + RUN})
    check(s == 201 and rv["rating"] == 5, "customer reviews the completed job", rv)
    s, body = customer.post(f"/api/v1/bookings/{b1['id']}/review", {"rating": 4})
    check(s == 409 and code_of(body) == "ALREADY_REVIEWED", "only one review per booking", body)
    s, rp = genie.post(f"/api/v1/genie/reviews/{rv['id']}/reply", {"reply": "Thank you!"})
    check(s == 200 and rp["genieReply"] == "Thank you!", "Genie replies to the review", rp)
    s, pub = anon.get(f"/api/v1/genies/{genie_id}/reviews?size=5")
    check(s == 200 and pub["items"][0]["id"] == rv["id"], "review is public, newest first", s)

    # ------------------------------------------------------------------ booking 2: online payment
    section("Booking 2: online payment through the gateway")
    s, b2 = book(slots[1], "ONLINE")
    genie.post(f"/api/v1/genie/bookings/{b2['id']}/accept")
    s, sc = customer.post(f"/api/v1/bookings/{b2['id']}/start-code")
    genie.post(f"/api/v1/genie/bookings/{b2['id']}/start", {"code": sc["code"]})
    s, d2 = genie.post(f"/api/v1/genie/bookings/{b2['id']}/complete", {"cashCollected": False})
    check(s == 200 and d2["paymentStatus"] == "UNPAID", "online job completes unpaid", d2.get("paymentStatus"))
    s, c2 = customer.put(f"/api/v1/bookings/{b2['id']}/tip", {"tipAmount": 50})
    check(s == 200 and float(c2["tipAmount"]) == 50 and "PAY" in c2["allowedActions"], "customer adds a tip after the job", c2.get("allowedActions"))
    s, order = customer.post(f"/api/v1/bookings/{b2['id']}/payment-order")
    check(s == 201 and float(order["amount"]) == float(c2["totalAmount"]), "payment order for the full total", order)
    s, again = customer.post(f"/api/v1/bookings/{b2['id']}/payment-order")
    check(again["paymentId"] == order["paymentId"], "repeat order request reuses the pending order", again.get("paymentId"))
    s, body = customer.post(f"/api/v1/payments/{order['paymentId']}/confirm", {"providerPaymentId": "fake_pay_x", "signature": "bad"})
    check(s == 400 and code_of(body) == "INVALID_SIGNATURE", "forged payment confirmation is rejected", body)
    s, order = customer.post(f"/api/v1/bookings/{b2['id']}/payment-order")
    w_before = genie.get("/api/v1/genie/wallet")[1]
    pay_id = "fake_pay_" + RUN
    sig = hmac.new(WEBHOOK_SECRET.encode(), f"{order['orderId']}|{pay_id}".encode(), hashlib.sha256).hexdigest()
    s, paid = customer.post(f"/api/v1/payments/{order['paymentId']}/confirm", {"providerPaymentId": pay_id, "signature": sig})
    check(s == 200 and paid["status"] == "SUCCEEDED", "correctly signed confirmation succeeds", paid)
    s, c2 = customer.get(f"/api/v1/bookings/{b2['id']}")
    check(c2["paymentStatus"] == "PAID", "booking is PAID", c2["paymentStatus"])
    w_after = genie.get("/api/v1/genie/wallet")[1]
    share = round(float(c2["totalAmount"]) - float(genie.get(f"/api/v1/genie/bookings/{b2['id']}")[1]["commissionAmount"]), 2)
    check(abs(float(w_after["balance"]) - float(w_before["balance"]) - share) < 0.01,
          "online job: Genie wallet goes up by total minus commission", (w_before["balance"], w_after["balance"], share))
    s, body = customer.post(f"/api/v1/bookings/{b2['id']}/payment-order")
    check(s == 422 and code_of(body) == "NOTHING_TO_PAY", "nothing left to pay", body)

    # ------------------------------------------------------------------ booking 3: late cancellation fee
    section("Booking 3: late cancellation fee (free window set to 48 h for this test)")
    s, b3 = book(slots[2])
    genie.post(f"/api/v1/genie/bookings/{b3['id']}/accept")
    s, v3 = customer.get(f"/api/v1/bookings/{b3['id']}")
    check(float(v3["cancellationFeeIfCancelledNow"]) == 49.0, "customer is told the fee before cancelling", v3.get("cancellationFeeIfCancelledNow"))
    s, c3 = customer.post(f"/api/v1/bookings/{b3['id']}/cancel", {"reason": "Plans changed"})
    check(s == 200 and c3["status"] == "CANCELLED" and c3["cancellationFeeStatus"] == "DUE", "late cancel -> fee DUE", c3)
    s, body = book(slots[3])
    check(s == 422 and code_of(body) == "OUTSTANDING_FEE", "cannot book again with an unpaid fee", body)
    s, fee_order = customer.post(f"/api/v1/bookings/{b3['id']}/payment-order")
    check(s == 201 and fee_order["purpose"] == "CANCELLATION_FEE" and float(fee_order["amount"]) == 49.0, "fee payment order", fee_order)
    s, paid = customer.post(f"/api/v1/payments/{fee_order['paymentId']}/simulate?outcome=success")
    check(s == 200 and paid["status"] == "SUCCEEDED", "fee paid (fake gateway checkout)", paid)
    s, c3 = customer.get(f"/api/v1/bookings/{b3['id']}")
    check(c3["cancellationFeeStatus"] == "PAID", "fee marked PAID", c3["cancellationFeeStatus"])

    # ------------------------------------------------------------------ booking 4: free cancel + decline
    section("Free cancellation and Genie decline")
    s, b4 = book(slots[3])
    s, c4 = customer.post(f"/api/v1/bookings/{b4['id']}/cancel")
    check(s == 200 and c4["cancellationFeeStatus"] is None, "cancelling before the Genie accepts is free", c4.get("cancellationFeeStatus"))
    s, b5 = book(slots[3])
    check(s == 201, "the freed slot can be booked again", b5)
    s, d5 = genie.post(f"/api/v1/genie/bookings/{b5['id']}/decline", {"reason": "Fully booked that day"})
    check(s == 200 and d5["status"] == "REJECTED", "Genie declines -> REJECTED", d5.get("status"))

    # ------------------------------------------------------------------ reschedule
    section("Reschedule")
    late = free_slots(anon, genie_id, service_id, needed=40)
    far = [x for x in late if dt.datetime.fromisoformat(x.replace("Z", "+00:00")) > dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=52)]
    if check(len(far) >= 2, "found slots more than 48 h ahead", len(far)):
        s, b6 = book(far[0])
        genie.post(f"/api/v1/genie/bookings/{b6['id']}/accept")
        s, r6 = customer.post(f"/api/v1/bookings/{b6['id']}/reschedule", {"slotStart": far[1]})
        check(s == 200 and r6["status"] == "REQUESTED" and r6["rescheduledCount"] == 1,
              "reschedule moves the slot and asks the Genie to accept again", r6 if s != 200 else r6["status"])
        customer.post(f"/api/v1/bookings/{b6['id']}/cancel")

    # ------------------------------------------------------------------ genie cancellations -> flag
    section("Genie reliability flag after 3 cancellations")
    open_slots = free_slots(anon, genie_id, service_id, needed=3)
    for i, slot in enumerate(open_slots):
        s, b = book(slot)
        genie.post(f"/api/v1/genie/bookings/{b['id']}/accept")
        s, c = genie.post(f"/api/v1/genie/bookings/{b['id']}/cancel", {"reason": "Emergency"})
        check(s == 200 and c["status"] == "CANCELLED", f"Genie cancels accepted job {i + 1}", c.get("status"))
    s, flagged = admin.get("/api/v1/admin/genies?flagged=true")
    check(s == 200 and any(g["id"] == genie_id for g in flagged["items"]), "admin sees the Genie flagged", s)
    s, _ = admin.post(f"/api/v1/admin/genies/{genie_id}/clear-flag")
    check(s == 204, "admin clears the flag", s)

    # ------------------------------------------------------------------ onboarding a new genie
    section("New Genie: onboarding, KYC and admin verification")
    newbie = Session("newbie")
    phone = "8" + str(int(time.time() * 1000))[-9:]
    s, reg = register(newbie, f"Nikhil Test {RUN}", phone, role="GENIE")
    s, np_ = newbie.get("/api/v1/genie/profile")
    check(s == 200 and np_["verificationStatus"] == "REGISTERED" and not np_["canSubmit"], "new Genie starts REGISTERED", np_.get("missingSteps"))
    s, body = newbie.post("/api/v1/genie/profile/submit")
    check(s == 422 and code_of(body) == "ONBOARDING_INCOMPLETE", "cannot submit an incomplete profile", body)
    s, _ = newbie.put("/api/v1/genie/profile", {"bio": "Certified plumber with 6 years of home repair experience.",
                                                "experienceYears": 6, "baseArea": "Maninagar", "baseLat": 22.9962,
                                                "baseLng": 72.6030, "serviceRadiusKm": 8, "payoutUpiId": "nikhil@okbank"})
    check(s == 200, "profile and payout UPI saved", s)
    s, cat = anon.get("/api/v1/categories/plumber")
    svc = cat["services"][0]
    s, body = newbie.put("/api/v1/genie/services", {"services": [{"serviceId": svc["id"], "priceOverride": float(svc["basePrice"]) * 5}]})
    check(s == 400 and code_of(body) == "PRICE_OUT_OF_RANGE", "price must be 0.5x to 3x the base price", body)
    s, _ = newbie.put("/api/v1/genie/services", {"services": [{"serviceId": svc["id"], "priceOverride": float(svc["basePrice"]) * 1.2}]})
    check(s == 200, "services and own price saved", s)
    s, body = newbie.put("/api/v1/genie/availability", {"windows": [{"dayOfWeek": 1, "startTime": "09:00", "endTime": "13:00"},
                                                                     {"dayOfWeek": 1, "startTime": "12:00", "endTime": "18:00"}]})
    check(s == 400 and code_of(body) == "OVERLAPPING_AVAILABILITY", "overlapping availability is rejected", body)
    windows = [{"dayOfWeek": d, "startTime": "09:00", "endTime": "18:00"} for d in range(1, 8)]
    s, _ = newbie.put("/api/v1/genie/availability", {"windows": windows})
    check(s == 200, "weekly availability saved", s)
    raw, ctype = multipart({"docType": "AADHAAR", "last4": "4321"}, "file", "aadhaar.pdf", b"%PDF-1.4 test document", "application/pdf")
    s, doc = newbie.call("POST", "/api/v1/genie/documents", raw=raw, content_type=ctype)
    check(s == 201 and doc["maskedNumber"] == "XXXX-XXXX-4321", "Aadhaar uploaded, only the masked number is stored", doc)
    raw, ctype = multipart({"docType": "SELFIE"}, "file", "selfie.jpg", b"not really an image", "image/jpeg")
    s, body = newbie.call("POST", "/api/v1/genie/documents", raw=raw, content_type=ctype)
    check(s == 400 and code_of(body) == "UNSUPPORTED_FILE", "a fake JPG is rejected by its real content", body)
    raw, ctype = multipart({"docType": "SELFIE"}, "file", "selfie.jpg", b"\xff\xd8\xff\xe0 fake jpeg bytes", "image/jpeg")
    s, selfie = newbie.call("POST", "/api/v1/genie/documents", raw=raw, content_type=ctype)
    check(s == 201, "selfie uploaded", selfie)
    s, sub = newbie.post("/api/v1/genie/profile/submit")
    check(s == 200 and sub["verificationStatus"] == "UNDER_REVIEW", "submitted for review", sub.get("verificationStatus") if isinstance(sub, dict) else sub)
    new_id = sub["id"]
    s, q = admin.get("/api/v1/admin/genies?status=UNDER_REVIEW")
    check(any(g["id"] == new_id for g in q["items"]), "admin sees them in the verification queue", s)
    s, f = admin.get(f"/api/v1/admin/genie-documents/{doc['id']}/file")
    check(s == 200 and f.startswith(b"%PDF"), "admin can open the KYC file", s)
    s, body = admin.post(f"/api/v1/admin/genies/{new_id}/decision", {"decision": "NEEDS_CHANGES"})
    check(s == 400 and code_of(body) == "NOTE_REQUIRED", "asking for changes needs a note", body)
    s, _ = admin.post(f"/api/v1/admin/genies/{new_id}/decision", {"decision": "NEEDS_CHANGES", "note": "Please upload a clearer selfie"})
    s, np_ = newbie.get("/api/v1/genie/profile")
    check(np_["verificationStatus"] == "NEEDS_CHANGES" and np_["verificationNote"], "Genie sees NEEDS_CHANGES with the note", np_.get("verificationStatus"))
    newbie.delete(f"/api/v1/genie/documents/{selfie['id']}")
    raw, ctype = multipart({"docType": "SELFIE"}, "file", "selfie2.png", b"\x89PNG\r\n\x1a\n fake png", "image/png")
    newbie.call("POST", "/api/v1/genie/documents", raw=raw, content_type=ctype)
    s, sub = newbie.post("/api/v1/genie/profile/submit")
    check(s == 200 and sub["verificationStatus"] == "UNDER_REVIEW", "Genie fixes it and resubmits", s)
    s, dec = admin.post(f"/api/v1/admin/genies/{new_id}/decision", {"decision": "APPROVE"})
    check(s == 200 and dec["profile"]["verificationStatus"] == "APPROVED"
          and all(d["status"] == "APPROVED" for d in dec["profile"]["documents"]), "admin approves; documents approved too", s)
    s, _ = newbie.post("/api/v1/genie/status", {"online": True})
    s2, listed = anon.get("/api/v1/genies?category=plumber&limit=50")
    check(s == 200 and any(g["id"] == new_id for g in listed), "approved Genie goes online and is listed publicly", s)
    s, n = newbie.get("/api/v1/notifications")
    check(s == 200 and any(x["type"] == "GENIE_APPROVED" for x in n["items"]), "Genie got an approval notification", s)

    # ------------------------------------------------------------------ webhook
    section("Payment webhook (server to server)")
    s, b7 = book(free_slots(anon, genie_id, service_id)[0], "ONLINE")
    genie.post(f"/api/v1/genie/bookings/{b7['id']}/accept")
    s, sc = customer.post(f"/api/v1/bookings/{b7['id']}/start-code")
    genie.post(f"/api/v1/genie/bookings/{b7['id']}/start", {"code": sc["code"]})
    genie.post(f"/api/v1/genie/bookings/{b7['id']}/complete", {"cashCollected": False})
    s, o7 = customer.post(f"/api/v1/bookings/{b7['id']}/payment-order")
    body = json.dumps({"event": "payment.captured", "orderId": o7["orderId"], "paymentId": "fake_pay_wh_" + RUN}).encode()
    s, _ = anon.call("POST", "/api/v1/payments/webhook/fake", raw=body, content_type="application/json",
                     headers={"X-Signature": "not-valid"})
    check(s == 401, "webhook with a bad signature is refused", s)
    sig = hmac.new(WEBHOOK_SECRET.encode(), body, hashlib.sha256).hexdigest()
    s, _ = anon.call("POST", "/api/v1/payments/webhook/fake", raw=body, content_type="application/json", headers={"X-Signature": sig})
    s2, c7 = customer.get(f"/api/v1/bookings/{b7['id']}")
    check(s == 200 and c7["paymentStatus"] == "PAID", "signed webhook marks the booking PAID", (s, c7.get("paymentStatus")))

    # ------------------------------------------------------------------ admin
    section("Admin: catalog, users, bookings, payouts, analytics, inbox")
    slug = "smoke-" + RUN
    s, newcat = admin.post("/api/v1/admin/categories", {"slug": slug, "name": f"Smoke {RUN}", "description": "Test category",
                                                        "icon": "sparkles", "sortOrder": 99, "active": True})
    check(s == 201, "create a category", newcat)
    s, newsvc = admin.post("/api/v1/admin/services", {"categoryId": newcat["id"], "slug": slug + "-svc", "name": f"Window washing {RUN}",
                                                      "basePrice": 299, "durationMinutes": 60, "active": True})
    check(s == 201, "create a service", newsvc)
    s, hits = anon.get(f"/api/v1/search?q={RUN}")
    check(s == 200 and any(h["serviceId"] == newsvc["id"] for h in hits), "new service is searchable", hits)
    s, _ = admin.put(f"/api/v1/admin/categories/{newcat['id']}", {**{k: newcat[k] for k in ("slug", "name", "description", "icon")},
                                                                  "imageUrl": None, "sortOrder": 99, "active": False})
    s2, cats = anon.get("/api/v1/categories")
    check(s == 200 and all(c["slug"] != slug for c in cats), "deactivated category disappears for customers", s)
    s, users = admin.get(f"/api/v1/admin/users?q={phone}")
    check(s == 200 and users["total"] == 1, "admin finds a user", users.get("total") if isinstance(users, dict) else users)
    s, _ = admin.put(f"/api/v1/admin/users/{other.user['id']}/status", {"status": "SUSPENDED"})
    s2, body = Session("again").login(other.user["phone"])
    check(s == 204 and s2 == 401 and code_of(body) == "ACCOUNT_DISABLED", "suspended user cannot log in", (s, s2, body))
    admin.put(f"/api/v1/admin/users/{other.user['id']}/status", {"status": "ACTIVE"})
    s, found = admin.get(f"/api/v1/admin/bookings?q={b1['bookingRef']}")
    check(s == 200 and found["total"] == 1, "admin finds a booking by reference", s)
    s, full = admin.get(f"/api/v1/admin/bookings/{b1['id']}")
    check(s == 200 and len(full["history"]) >= 4, "admin sees the full status history", len(full.get("history", [])))
    s, gen = admin.post("/api/v1/admin/payouts/generate", {"periodEnd": dt.date.today().isoformat()})
    check(s == 200, "generate payouts", gen)
    s, pays = admin.get("/api/v1/admin/payouts?status=PENDING&size=100")
    mine = [p for p in pays["items"] if p["genieId"] == genie_id] if s == 200 else []
    if mine:
        s, pd = admin.post(f"/api/v1/admin/payouts/{mine[0]['id']}/mark-paid", {"reference": "UPI" + RUN})
        check(s == 200 and pd["status"] == "PAID", "mark a payout paid", pd)
    else:
        check(True, "no positive balance for this Genie this week (cash jobs owe commission)")
    s, wal = admin.post(f"/api/v1/admin/genies/{genie_id}/settlements", {"amount": 10, "reference": "CASH" + RUN})
    check(s == 200, "record a commission settlement from a Genie", wal)
    s, ov = admin.get("/api/v1/admin/analytics/overview")
    check(s == 200 and ov["completed"] >= 1 and len(ov["daily"]) == 30, "analytics overview (30 days)", {k: ov.get(k) for k in ("completed", "gmv")})
    s, gs = genie.get("/api/v1/genie/analytics?days=7")
    check(s == 200 and gs["completed"] >= 1, "Genie analytics", gs.get("completed") if isinstance(gs, dict) else gs)
    anon.post("/api/v1/contact", {"name": "Smoke", "email": "smoke@example.com", "subject": "Hello " + RUN, "message": "Test message"})
    s, inbox = admin.get("/api/v1/admin/contact-messages?status=NEW")
    msg = next((m for m in inbox["items"] if m["subject"] == "Hello " + RUN), None) if s == 200 else None
    if check(msg is not None, "contact message reaches the admin inbox", s):
        s, m = admin.patch(f"/api/v1/admin/contact-messages/{msg['id']}", {"status": "RESOLVED"})
        check(s == 200 and m["resolvedAt"], "admin resolves it", m)

    # ------------------------------------------------------------------ notifications
    section("Notifications")
    s, uc = customer.get("/api/v1/notifications/unread-count")
    check(s == 200 and uc["unread"] > 0, "customer has unread notifications", uc)
    s, gn = genie.get("/api/v1/notifications?size=50")
    types = {n["type"] for n in gn["items"]}
    check({"BOOKING_REQUESTED", "BOOKING_CANCELLED", "REVIEW_RECEIVED", "PAYMENT_RECEIVED"} <= types, "Genie notification types", sorted(types))
    s, _ = customer.post("/api/v1/notifications/read-all")
    s2, uc = customer.get("/api/v1/notifications/unread-count")
    check(uc["unread"] == 0, "mark all read", uc)

    launch_readiness(anon, customer, genie, admin,
                     {"book": book, "genie_id": genie_id, "service_id": service_id, "b1": b1, "b2": b2})

    customer.delete(f"/api/v1/me/favourites/{genie_id}")
    customer.delete(f"/api/v1/me/addresses/{address_id}")


# ====================================================================== Phase 2A: launch readiness

def jpeg_bytes():
    return b"\xff\xd8\xff\xe0" + b"\x00" * 64


def launch_readiness(anon, customer, genie, admin, ctx):
    book, genie_id, service_id = ctx["book"], ctx["genie_id"], ctx["service_id"]
    b1, b2 = ctx["b1"], ctx["b2"]

    # ------------------------------------------------------------------ OTP
    section("OTP login, resend limits and password reset")
    s, ch = anon.post("/api/v1/auth/otp/request", {"identifier": "9111111111", "purpose": "LOGIN"})
    check(s == 202 and ch.get("challengeId"), "unknown number still gets 202 and a challenge (no account probing)", s)
    phone = customer.user["phone"]
    before = seen(sms_address(phone))
    otp_user = Session("otp")
    s, ch = otp_user.post("/api/v1/auth/otp/request", {"identifier": phone, "purpose": "LOGIN"})
    check(s == 202 and ch["channel"] == "SMS" and "*" in ch["destination"], "login code requested; destination is masked", ch)
    s, body = otp_user.post("/api/v1/auth/otp/request", {"identifier": phone, "purpose": "LOGIN"})
    check(s == 429 and code_of(body) == "OTP_TOO_SOON" and body.get("retryAfterSeconds"), "asking again within 30 s is refused", body)
    msg = wait_for_message(sms_address(phone), before)
    code = otp_from(msg)
    check(code is not None, "the SMS (via Mailpit) carries a 6-digit code", (msg or {}).get("Text"))
    wrong = "000000" if code != "000000" else "111111"
    s, body = otp_user.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": wrong})
    check(s == 400 and code_of(body) == "OTP_INVALID" and body.get("attemptsLeft") == 4, "a wrong code counts an attempt", body)
    s, ok = otp_user.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": code})
    check(s == 200 and ok["purpose"] == "LOGIN" and ok["accessToken"], "the right code logs in", ok)
    otp_user.token = ok.get("accessToken")
    s, _ = otp_user.post("/api/v1/auth/refresh")
    check(s == 200, "OTP login also sets the refresh cookie", s)
    s, body = otp_user.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": code})
    check(s == 400 and code_of(body) == "OTP_EXPIRED", "a code works only once", body)

    reset_user = Session("reset")
    rphone = "7" + str(int(time.time() * 1000))[-9:]
    register(reset_user, f"Reset Test {RUN}", rphone)
    before = seen(sms_address(rphone))
    s, ch = request_otp(anon, rphone, "RESET_PASSWORD")
    code = otp_from(wait_for_message(sms_address(rphone), before))
    s, rv = anon.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": code})
    check(s == 200 and rv["resetToken"] and rv["accessToken"] is None, "a reset code gives a reset token, not a login", rv)
    s, _ = anon.post("/api/v1/auth/password/reset", {"resetToken": rv["resetToken"], "newPassword": "NewPass@2026"})
    s2, _ = Session("x").login(rphone, "NewPass@2026")
    s3, _ = Session("x").login(rphone, PASSWORD)
    check(s == 204 and s2 == 200 and s3 == 401, "password reset works; the old password doesn't", (s, s2, s3))
    s, body = anon.post("/api/v1/auth/password/reset", {"resetToken": rv["resetToken"], "newPassword": "Other@2026"})
    check(s == 400 and code_of(body) == "RESET_TOKEN_INVALID", "a reset token works once", body)
    s, _ = reset_user.post("/api/v1/auth/refresh")
    check(s == 401, "the reset signed out every device", s)
    time.sleep(2)
    check(any("password was changed" in m.get("Snippet", "") for m in mailpit_messages(sms_address(rphone))),
          "a security SMS says the password was changed", None)

    section("Password lockout")
    lock_user = Session("lock")
    lphone = "6" + str(int(time.time() * 1000))[-9:]
    register(lock_user, f"Lockout Test {RUN}", lphone, verify=False)
    statuses = [Session("x").login(lphone, "Wrong@pass1")[0] for _ in range(10)]
    check(statuses[:9] == [401] * 9 and statuses[9] == 429, "the 10th wrong password locks password login", statuses)
    s, body = Session("x").login(lphone)
    check(s == 429 and code_of(body) == "LOGIN_LOCKED", "even the right password is refused while locked", body)
    before = seen(sms_address(lphone))
    s, ch = request_otp(anon, lphone, "LOGIN")
    code = otp_from(wait_for_message(sms_address(lphone), before))
    s, ok = anon.post("/api/v1/auth/otp/verify", {"challengeId": ch["challengeId"], "code": code})
    check(s == 200 and ok["user"]["phoneVerified"], "OTP login still works while locked (and verifies the phone)", s)

    section("Verification gate")
    unverified = Session("unverified")
    uphone = "9" + str(int(time.time() * 1000) + 7)[-9:]
    s, reg = register(unverified, f"Unverified {RUN}", uphone, verify=False)
    check(s == 201 and reg["user"]["phoneVerified"] is False and reg["user"]["pendingConsents"] == [],
          "sign-up with consent: phone not yet verified, nothing pending", reg.get("user"))
    gp = genie.get("/api/v1/genie/profile")[1]
    s, a = unverified.post("/api/v1/me/addresses", {"label": "Home", "line1": "5 Gate Rd", "area": "Paldi", "pincode": "380007",
                                                   "lat": gp["baseLat"], "lng": gp["baseLng"], "isDefault": True})
    slot = free_slots(anon, genie_id, service_id)[0]
    s, body = book(slot, who=unverified, address=a["id"])
    check(s == 403 and code_of(body) == "PHONE_NOT_VERIFIED", "an unverified phone can't book", body)
    s, body = anon.post("/api/v1/auth/register", {"fullName": "No Consent", "phone": "9" + str(int(time.time()))[-9:],
                                                   "password": PASSWORD})
    check(s == 400, "sign-up without accepting the Terms is refused", s)

    # ------------------------------------------------------------------ legal
    section("Legal pages and re-acceptance")
    s, terms = anon.get("/api/v1/legal/terms")
    check(s == 200 and terms["version"] >= 1 and "Terms" in terms["title"], "Terms are public", terms.get("title"))
    s, priv = anon.get("/api/v1/legal/privacy")
    check(s == 200 and "Grievance" in priv["body"], "Privacy Policy names the grievance officer", s)
    s, comp = anon.get("/api/v1/legal/company")
    check(s == 200 and "grievanceEmail" in comp, "company contact details are public", comp)
    s, draft = admin.put("/api/v1/admin/legal/terms/draft", {"title": "Terms of Service", "body": terms["body"] + "\n\nUpdated " + RUN,
                                                            "changeSummary": "Smoke test update", "requiresReacceptance": True})
    check(s == 200 and draft["publishedAt"] is None and draft["version"] == terms["version"] + 1, "admin saves a draft", draft.get("version"))
    s, pub = admin.post(f"/api/v1/admin/legal/{draft['id']}/publish")
    check(s == 200 and pub["publishedAt"], "admin publishes it (re-acceptance required)", s)
    s, me = customer.get("/api/v1/me")
    check(me["pendingConsents"] == ["TERMS"], "customer now has the new Terms pending", me.get("pendingConsents"))
    s, body = book(free_slots(anon, genie_id, service_id)[0])
    check(s == 428 and code_of(body) == "CONSENT_REQUIRED" and body["pendingConsents"] == ["TERMS"],
          "booking is blocked with 428 until accepted", body)
    for who in (customer, genie):
        s, res = who.post("/api/v1/me/consents", {"kinds": ["TERMS"]})
    check(s == 200 and res["pendingConsents"] == [], "customer and Genie accept the new version", res)
    s, hist = customer.get("/api/v1/me/consents")
    check(any(c["kind"] == "TERMS" and c["version"] == pub["version"] for c in hist), "the acceptance is recorded", s)

    # ------------------------------------------------------------------ messages
    section("Messages: preferences, outbox and admin view")
    s, prefs = customer.get("/api/v1/me/notification-preferences")
    promo = next(p for p in prefs if p["category"] == "PROMOTIONS")
    check(s == 200 and len(prefs) == 5 and not promo["whatsapp"], "5 categories; promotions are off by default", prefs)
    s, prefs = customer.put("/api/v1/me/notification-preferences",
                            [{"category": "BOOKING", "sms": True, "whatsapp": False, "email": True}])
    check(next(p for p in prefs if p["category"] == "BOOKING")["whatsapp"] is False, "customer turns off booking WhatsApps", s)
    cwa = f"{phone[-10:]}@whatsapp.progenie.local"
    gsms = sms_address(genie.user["phone"])
    before_c, before_g = seen(cwa), seen(gsms)
    s, bm = book(free_slots(anon, genie_id, service_id)[0])
    genie.post(f"/api/v1/genie/bookings/{bm['id']}/accept")
    gmsg = wait_for_message(gsms, before_g)
    check(gmsg is not None and bm["bookingRef"] in gmsg["Text"], "Genie gets an SMS for the new request", (gmsg or {}).get("Text"))
    time.sleep(3)
    check(not mailpit_messages(cwa, before_c), "no WhatsApp to the customer after they switched it off", None)
    customer.put("/api/v1/me/notification-preferences", [{"category": "BOOKING", "sms": True, "whatsapp": True, "email": True}])
    genie.post(f"/api/v1/genie/bookings/{bm['id']}/cancel", {"reason": "Smoke test cleanup"})
    s, sent = admin.get("/api/v1/admin/messages?status=SENT&size=5")
    check(s == 200 and sent["total"] > 0 and "*" in sent["items"][0]["destination"], "admin sees sent messages, masked", s)
    s, body = admin.post(f"/api/v1/admin/messages/{sent['items'][0]['id']}/retry")
    check(s == 422 and code_of(body) == "NOT_FAILED", "only failed messages can be retried", body)
    s, otps = admin.get("/api/v1/admin/messages?q=OTP&size=5")
    check(s == 200 and all(m["body"] == "[redacted]" for m in otps["items"] if m["status"] == "SENT"),
          "sent one-time codes are redacted from the outbox", None)

    # ------------------------------------------------------------------ receipts and refunds
    section("Receipts, credit notes and refunds")
    s, rec = customer.get(f"/api/v1/bookings/{b2['id']}/receipts")
    receipt = rec[0] if s == 200 and rec else {}
    check(s == 200 and len(rec) == 1 and receipt["kind"] == "RECEIPT" and receipt["number"].startswith("PG/"),
          "the online payment has a numbered receipt", rec)
    s, pdf = customer.get(f"/api/v1/receipts/{receipt['id']}/pdf")
    check(s == 200 and pdf[:4] == b"%PDF", "receipt PDF downloads", s)
    s, _ = genie.get(f"/api/v1/receipts/{receipt['id']}/pdf")
    check(s in (403, 404), "someone else's receipt is not available", s)
    mails = [m for m in mailpit_messages(customer.user["email"]) if receipt["number"] in m["Subject"]]
    check(mails and mailpit_message(mails[0]["ID"])["Attachments"], "receipt emailed with the PDF attached", len(mails))
    s, pays = admin.get(f"/api/v1/admin/bookings/{b2['id']}/payments")
    pay = next(p for p in pays if p["status"] == "SUCCEEDED")
    w0 = float(genie.get("/api/v1/genie/wallet")[1]["balance"])
    s, body = admin.post(f"/api/v1/admin/payments/{pay['id']}/refunds", {"amount": float(pay["amount"]) + 1, "reason": "Too much"})
    check(s == 422 and code_of(body) == "REFUND_TOO_LARGE", "a refund can't exceed what was paid", body)
    s, rf = admin.post(f"/api/v1/admin/payments/{pay['id']}/refunds", {"amount": 50, "liability": "GENIE", "reason": "Late arrival"})
    check(s == 201 and rf["status"] == "PROCESSED" and rf["method"] == "GATEWAY", "partial refund through the gateway", rf)
    s, c2 = customer.get(f"/api/v1/bookings/{b2['id']}")
    check(c2["paymentStatus"] == "PARTIALLY_REFUNDED", "booking shows PARTIALLY_REFUNDED", c2.get("paymentStatus"))
    w1 = float(genie.get("/api/v1/genie/wallet")[1]["balance"])
    check(0 < round(w0 - w1, 2) <= 50, "Genie-liable refund comes out of the Genie's wallet (minus commission)", (w0, w1))
    s, rec = customer.get(f"/api/v1/bookings/{b2['id']}/receipts")
    check(any(r["kind"] == "CREDIT_NOTE" and r["number"].startswith("PG-CN/") for r in rec), "a credit note is issued", rec)
    s, pays1 = admin.get(f"/api/v1/admin/bookings/{b1['id']}/payments")
    cash = next(p for p in pays1 if p["status"] == "SUCCEEDED")
    s, body = admin.post(f"/api/v1/admin/payments/{cash['id']}/refunds", {"amount": 20, "reason": "Goodwill", "method": "GATEWAY"})
    check(s == 422 and code_of(body) == "GATEWAY_REFUND_NOT_POSSIBLE", "cash can't be refunded through the gateway", body)
    s, body = admin.post(f"/api/v1/admin/payments/{cash['id']}/refunds", {"amount": 20, "reason": "Goodwill"})
    check(s == 400 and code_of(body) == "REFERENCE_REQUIRED", "a manual refund needs the UPI reference", body)
    s, rf2 = admin.post(f"/api/v1/admin/payments/{cash['id']}/refunds",
                        {"amount": 20, "liability": "PLATFORM", "reason": "Goodwill", "manualReference": "UPI" + RUN})
    w2 = float(genie.get("/api/v1/genie/wallet")[1]["balance"])
    check(s == 201 and rf2["status"] == "PROCESSED" and abs(w2 - w1) < 0.01, "goodwill refund doesn't touch the Genie's wallet", (s, w1, w2))
    body = json.dumps({"id": "evt_" + RUN, "event": "payment.failed", "orderId": "nope", "reason": "x"}).encode()
    sig = hmac.new(WEBHOOK_SECRET.encode(), body, hashlib.sha256).hexdigest()
    r1 = anon.call("POST", "/api/v1/payments/webhook/fake", raw=body, content_type="application/json", headers={"X-Signature": sig})[0]
    r2 = anon.call("POST", "/api/v1/payments/webhook/fake", raw=body, content_type="application/json", headers={"X-Signature": sig})[0]
    check(r1 == 200 and r2 == 200, "a redelivered webhook is acknowledged and ignored", (r1, r2))

    # ------------------------------------------------------------------ complaints
    section("Complaints: report, thread, resolution")
    s, bn = book(free_slots(anon, genie_id, service_id)[0])
    s, body = customer.post(f"/api/v1/bookings/{bn['id']}/tickets", {"category": "QUALITY", "subject": "x", "description": "y"})
    check(s == 422 and code_of(body) == "CANNOT_REPORT", "a booking no Genie accepted can't be reported", body)
    customer.post(f"/api/v1/bookings/{bn['id']}/cancel")
    s, t = customer.post(f"/api/v1/bookings/{b2['id']}/tickets",
                         {"category": "QUALITY", "subject": "Switch stopped working", "description": "The new switch stopped working the next day."})
    check(s == 201 and t["status"] == "OPEN" and t["priority"] == "NORMAL" and t["ticketRef"].startswith("PGT-"), "customer reports a problem", t)
    s, body = customer.post(f"/api/v1/bookings/{b2['id']}/tickets", {"category": "OTHER", "subject": "Again", "description": "Again"})
    check(s == 409 and code_of(body) == "TICKET_ALREADY_OPEN", "one open report per booking", body)
    s, gl = genie.get("/api/v1/tickets?status=ACTIVE")
    check(s == 200 and any(x["id"] == t["id"] for x in gl["items"]), "the Genie sees the report on their job", s)
    s, gt = genie.post(f"/api/v1/tickets/{t['id']}/messages", {"body": "Sorry! Happy to come back and fix it."})
    check(s == 200 and len(gt["messages"]) == 1, "the Genie replies", s)
    raw, ctype = multipart({}, "file", "fake.jpg", b"not an image", "image/jpeg")
    s, body = customer.call("POST", f"/api/v1/tickets/{t['id']}/attachments", raw=raw, content_type=ctype)
    check(s == 400 and code_of(body) == "UNSUPPORTED_FILE", "a fake photo is rejected by its bytes", body)
    raw, ctype = multipart({}, "file", "switch.jpg", jpeg_bytes(), "image/jpeg")
    s, att = customer.call("POST", f"/api/v1/tickets/{t['id']}/attachments", raw=raw, content_type=ctype)
    check(s == 201 and att["contentType"] == "image/jpeg", "a real photo is attached", att)
    s, f = admin.get(f"/api/v1/tickets/{t['id']}/attachments/{att['id']}")
    check(s == 200 and f[:3] == b"\xff\xd8\xff", "admin opens the photo", s)
    s, q = admin.get("/api/v1/admin/tickets?status=ACTIVE&size=100")
    check(s == 200 and any(x["id"] == t["id"] for x in q["items"]), "the report is in the admin queue", s)
    s, at = admin.post(f"/api/v1/admin/tickets/{t['id']}/assign")
    check(s == 200 and at["status"] == "IN_REVIEW" and at["assignedTo"] == admin.user["id"], "admin takes it (IN_REVIEW)", at.get("status"))
    admin.post(f"/api/v1/admin/tickets/{t['id']}/reply", {"body": "Internal: Genie has 0 prior issues", "internal": True})
    s, ar = admin.post(f"/api/v1/admin/tickets/{t['id']}/reply", {"body": "Can you share when it stopped?", "awaitingReply": True})
    check(s == 200 and ar["status"] == "AWAITING_REPLY" and ar["firstRespondedAt"], "admin asks the customer (AWAITING_REPLY)", ar.get("status"))
    s, ct = customer.get(f"/api/v1/tickets/{t['id']}")
    check(all(not m["internal"] for m in ct["messages"]) and len(ct["messages"]) == 2, "internal notes stay hidden from the customer", len(ct["messages"]))
    s, ct = customer.post(f"/api/v1/tickets/{t['id']}/messages", {"body": "The next morning around 9."})
    check(s == 200 and ct["status"] == "IN_REVIEW", "customer answers (back to IN_REVIEW)", ct.get("status"))
    redo_slot = free_slots(anon, genie_id, service_id)[0]
    s, res = admin.post(f"/api/v1/admin/tickets/{t['id']}/resolve", {
        "actions": ["REFUND", "REDO", "STRIKE"], "note": "Sorry about this. We refunded part of the job and booked a free redo.",
        "refund": {"amount": 20, "liability": "SPLIT", "genieShare": 10}, "redo": {"slotStart": redo_slot}})
    check(s == 200 and res["status"] == "RESOLVED" and res["refundId"] and res["redoBookingId"], "admin resolves: refund + free redo + strike", res)
    s, redo = customer.get(f"/api/v1/bookings/{res['redoBookingId']}")
    check(s == 200 and float(redo["totalAmount"]) == 0 and redo["paymentStatus"] == "WAIVED", "the redo is a free booking for the same Genie", redo)
    s, ct = customer.post(f"/api/v1/tickets/{t['id']}/accept")
    check(s == 200 and ct["status"] == "CLOSED", "customer accepts the resolution (CLOSED)", ct.get("status"))
    s, body = customer.post(f"/api/v1/tickets/{t['id']}/reopen", {"body": "Changed my mind"})
    check(s == 422 and code_of(body) == "CANNOT_REOPEN", "a closed report can't be reopened", body)
    genie.post(f"/api/v1/genie/bookings/{res['redoBookingId']}/decline", {"reason": "Smoke test cleanup"})

    asms = sms_address("9000000001")
    before = seen(asms)
    s, st = customer.post(f"/api/v1/bookings/{b1['id']}/tickets",
                          {"category": "SAFETY", "subject": "Genie was rude", "description": "Raised voice at my parents."})
    check(s == 201 and st["priority"] == "URGENT", "a safety report is URGENT", st.get("priority"))
    amsg = wait_for_message(asms, before)
    check(amsg is not None and st["ticketRef"] in amsg["Text"], "every admin gets an SMS for a safety report", (amsg or {}).get("Text"))
    s, rj = admin.post(f"/api/v1/admin/tickets/{st['id']}/reject", {"reason": "We spoke to both sides; no evidence."})
    check(s == 200 and rj["status"] == "REJECTED", "admin closes it without action", s)
    s, ro = customer.post(f"/api/v1/tickets/{st['id']}/reopen", {"body": "I have a recording."})
    check(s == 200 and ro["status"] == "IN_REVIEW", "customer reopens within 7 days", ro.get("status"))
    s, res = admin.post(f"/api/v1/admin/tickets/{st['id']}/resolve", {"actions": ["NO_ACTION", "STRIKE"], "note": "x"})
    check(s == 400 and code_of(res) == "INVALID_ACTION", "no-action can't be combined", res)
    s, res = admin.post(f"/api/v1/admin/tickets/{st['id']}/resolve", {"actions": ["STRIKE"], "note": "Warning issued to the Genie."})
    check(s == 200 and res["status"] == "RESOLVED", "resolved with a Genie warning", s)
    s, pr = customer.post("/api/v1/tickets/privacy", {"subject": "Correct my name", "description": "Please fix the spelling."})
    check(s == 201 and pr["type"] == "PRIVACY" and pr["bookingId"] is None, "privacy request goes to the same queue", pr.get("type"))

    # ------------------------------------------------------------------ privacy
    section("Privacy: data export and account deletion")
    s, job = customer.post("/api/v1/me/data-export")
    check(s == 202 and job["status"] == "PENDING", "customer asks for a copy of their data", job)
    ready = None
    for _ in range(40):
        s, jobs = customer.get("/api/v1/me/data-export")
        ready = next((j for j in jobs if j["id"] == job["id"] and j["status"] == "READY"), None)
        if ready:
            break
        time.sleep(1)
    check(ready is not None, "the export is built in the background", jobs[:1] if isinstance(jobs, list) else jobs)
    if ready:
        s, z = customer.get(f"/api/v1/me/data-export/{job['id']}/file")
        import io
        import zipfile
        names = zipfile.ZipFile(io.BytesIO(z)).namelist() if s == 200 else []
        check({"profile.json", "bookings.json", "payments.json", "consents.json", "tickets.json"} <= set(names),
              "the ZIP holds profile, bookings, payments, consents and tickets", names)
        profile = json.loads(zipfile.ZipFile(io.BytesIO(z)).read("profile.json"))
        check("password_hash" not in profile, "no password hash in the export", sorted(profile)[:5])
    s, body = customer.post("/api/v1/me/data-export")
    check(s == 429 and code_of(body) == "EXPORT_RECENT", "one export per day", body)
    s, gs = genie.get("/api/v1/me/deletion-request")
    check(s == 200 and any(b["code"] in ("WALLET_NOT_SETTLED", "UPCOMING_BOOKINGS") for b in gs["blockers"]),
          "a Genie with money or jobs outstanding sees what blocks deletion", gs.get("blockers"))
    s, body = genie.post("/api/v1/me/deletion-request", {"reason": "test"})
    check(s == 422 and code_of(body) == "DELETION_BLOCKED", "so the deletion is refused", body)
    leaver = Session("leaver")
    lvphone = "8" + str(int(time.time() * 1000) + 3)[-9:]
    register(leaver, f"Leaver {RUN}", lvphone)
    s, d = leaver.post("/api/v1/me/deletion-request", {"reason": "Moving away"})
    check(s == 201 and d["status"] == "SCHEDULED" and d["scheduledFor"], "account deletion is scheduled 7 days out", d)
    s, me = leaver.get("/api/v1/me")
    check(me["deletionScheduledFor"] is not None, "/me shows the pending deletion", me.get("deletionScheduledFor"))
    s, _ = leaver.delete("/api/v1/me/deletion-request")
    s2, me = leaver.get("/api/v1/me")
    check(s == 204 and me["deletionScheduledFor"] is None, "the user cancels it during the grace period", (s, me.get("deletionScheduledFor")))
    s, dl = admin.get("/api/v1/admin/deletion-requests")
    check(s == 200 and dl["total"] >= 1, "admin sees deletion requests", s)


if __name__ == "__main__":
    print(f"ProGenie API smoke test against {BASE} (run {RUN})")
    try:
        main()
    except Exception as exc:  # a crash is a failed check, with the reason shown
        import traceback
        traceback.print_exc()
        failed += 1
    print(f"\n{passed} passed, {failed} failed")
    sys.exit(0 if failed == 0 else 1)
