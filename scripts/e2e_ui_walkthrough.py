"""
ProGenie end-to-end UI walkthrough in a real browser (Playwright + Chromium).

Customer books on a phone → Genie accepts → customer shows the start code → Genie starts and completes with extras
→ customer pays online (a declined try first) and reviews → Genie replies → a new Genie signs up, completes the
get-verified wizard with KYC uploads and is approved by the admin → admin, tablet and 360 px sweeps.
Phase 2A: sign-up consent and phone verification (code read from Mailpit), receipt download, report a problem →
admin takes, replies and resolves with a split partial refund → customer accepts and gets a credit note,
one-time-code login, notification preferences, data export download, and publishing a policy that everyone must
accept again (the consent dialog).
Every page is checked for horizontal overflow and console errors; screenshots land in scripts/ui-shots/.

Needs: Mailpit on http://localhost:8025 (docker compose), the API started with relaxed timing (see docs/LOCAL_SETUP.md, step 12) on a freshly reset database,
the web app on http://localhost:4200 (npm start), and `pip install playwright && python3 -m playwright install chromium`.
Usage: python3 scripts/e2e_ui_walkthrough.py        (exit code 0 = all checks passed)
"""
import os, re, sys, json, time, urllib.parse, urllib.request
from playwright.sync_api import sync_playwright, expect

BASE = "http://localhost:4200"
BASE = os.environ.get("PROGENIE_WEB", BASE)
MAILPIT = os.environ.get("MAILPIT", "http://localhost:8025").rstrip("/")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui-shots")
os.makedirs(OUT, exist_ok=True)
PW = "ProGenie@123"
problems = []
checks = 0


def ok(cond, msg):
    global checks
    checks += 1
    if not cond:
        problems.append(msg)
        print("  FAIL:", msg)
    else:
        print("  ok:", msg)


def has(page, text, timeout=8000, absent=False):
    """Waits until the text is (or is no longer) on the page."""
    end = time.time() + timeout / 1000
    while True:
        present = text.lower() in page.inner_text("body").lower()
        if present != absent:
            return True
        if time.time() > end:
            return False
        page.wait_for_timeout(200)


def watch(page, label):
    def on_console(m):
        if m.type == "error" and "tile.openstreetmap.org" not in m.text and "ERR_" not in m.text and "status of 401" not in m.text and "status of 4" not in m.text:
            problems.append(f"[{label}] console error: {m.text[:200]}")
    page.on("console", on_console)
    page.on("pageerror", lambda e: problems.append(f"[{label}] page error: {e}"))


def overflow(page, label):
    # Compare with the configured viewport: on mobile emulation innerWidth grows with overflowing content.
    w = [page.evaluate("() => document.documentElement.scrollWidth"), page.viewport_size["width"]]
    ok(w[0] <= w[1], f"{label}: no horizontal overflow ({w[0]} <= {w[1]})")


def shot(page, name, full=True):
    page.screenshot(path=os.path.join(OUT, name + ".png"), full_page=full)


def mail_ids(to):
    q = urllib.parse.quote(f"to:{to}")
    with urllib.request.urlopen(f"{MAILPIT}/api/v1/search?query={q}", timeout=10) as res:
        return [m["ID"] for m in json.loads(res.read())["messages"]]


def mail_code(to, since=(), timeout=25):
    """Waits for a new message to `to` (SMS arrive as <phone>@sms.progenie.local) and returns its 6-digit code."""
    end = time.time() + timeout
    while time.time() < end:
        fresh = [i for i in mail_ids(to) if i not in since]
        if fresh:
            with urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{fresh[0]}", timeout=10) as res:
                m = json.loads(res.read())
            found = re.search(r"\b(\d{6})\b", m.get("Subject", "") + " " + m.get("Text", ""))
            if found:
                return found.group(1)
        time.sleep(1)
    return None


def download(page, click):
    """Runs `click` and returns the downloaded file's name and size."""
    with page.expect_download(timeout=15000) as d:
        click()
    path = d.value.path()
    return d.value.suggested_filename, os.path.getsize(path)


def login(page, who):
    page.goto(BASE + "/login"); page.wait_for_load_state("networkidle")
    for attempt in range(3):
        page.fill("#identifier", who)
        page.locator("input[type=password]").fill(PW)
        page.get_by_role("button", name=re.compile("^log in$", re.I)).click()
        try:
            page.wait_for_url(lambda u: "/login" not in u, timeout=10000)
            break
        except Exception:
            if attempt == 2:
                raise
            page.reload(); page.wait_for_load_state("networkidle")
    page.wait_for_load_state("networkidle")


import atexit
PAGES = []
def dump():
    for i, pg in enumerate(PAGES):
        try:
            pg.screenshot(path=os.path.join(OUT, f"_last_{i}.png"), full_page=True)
        except Exception:
            pass

with sync_playwright() as p:
    try:
        browser = p.chromium.launch()
        IN = dict(timezone_id="Asia/Kolkata", locale="en-IN")
        desktop = dict(viewport={"width": 1366, "height": 860}, **IN)
        phone = dict(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, device_scale_factor=2, **IN)

        # ------------------------------------------------------------ customer books on a phone
        print("CUSTOMER (phone)")
        cctx = browser.new_context(**phone)
        cctx.set_default_timeout(12000); cp = cctx.new_page(); watch(cp, "customer"); PAGES.append(cp)
        login(cp, "customer@example.com")
        cp.goto(BASE + "/services/electrician"); cp.wait_for_load_state("networkidle")
        cp.get_by_role("button", name=re.compile("Fan installation")).first.click()
        cp.wait_for_timeout(600)
        ok(has(cp, "Offering"), "category page filters Genies by service")
        cp.goto(BASE + "/genies/1af855e6-31a8-5caa-bffa-6f864b9644ff"); cp.wait_for_load_state("networkidle")
        cp.get_by_role("button", name=re.compile("Fan installation")).click()
        cp.wait_for_selector("h1 button[aria-pressed]")
        if cp.locator("h1 button[aria-label='Save to favourites']").count():
            cp.locator("h1 button[aria-label='Save to favourites']").click()
        cp.wait_for_timeout(500)
        cp.get_by_role("button", name="Choose a time").click()
        cp.wait_for_url(re.compile(r"/book/")); cp.wait_for_load_state("networkidle")
        cp.wait_for_selector("[role=option]:not([disabled])")
        shot(cp, "phone-checkout-top", full=False)
        from datetime import datetime, timezone, timedelta
        today = datetime.now(timezone(timedelta(hours=5, minutes=30)))
        first_chip = cp.locator("pg-slot-picker [role=option]").first.inner_text().split()
        ok(first_chip[0].lower() == today.strftime("%a").lower() and first_chip[1] == str(today.day), f"day strip starts today in India ({first_chip[:2]} vs {today.strftime('%a %d')})")
        chosen = cp.locator("pg-slot-picker [role=option][aria-selected=true]").inner_text().split()
        # pick first free time
        cp.locator("pg-slot-picker button[aria-pressed]").first.click()
        cp.wait_for_timeout(300)
        summary_when = cp.locator("#summary").inner_text()
        ok(f"{chosen[0][:3].title()} {chosen[1]}" in summary_when, f"summary date matches the chosen day chip ({chosen[:2]})")
        cp.get_by_role("radio", name=re.compile("Pay online after the job")).click()
        cp.get_by_role("button", name="₹50").click()
        cp.fill("#notes", "Two ceiling fans. Ladder available.")
        cp.wait_for_timeout(700)
        body = cp.inner_text("body")
        ok("Total" in body and "Travel" in body, "live quote shows travel fee and total")
        overflow(cp, "phone checkout")
        shot(cp, "phone-checkout-full")
        cp.locator("div.sticky button.btn-gold").click()
        cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000)
        booking_url = cp.url
        booking_id = booking_url.rsplit("/", 1)[1]
        cp.wait_for_load_state("networkidle")
        ok(has(cp, "Waiting for"), "new booking shows 'Waiting for … to accept'")
        shot(cp, "phone-booking-requested")
        overflow(cp, "phone booking detail")

        # ------------------------------------------------------------ Genie accepts on desktop
        print("GENIE (desktop)")
        gctx = browser.new_context(**desktop)
        gp = gctx.new_page(); watch(gp, "genie"); PAGES.append(gp)
        login(gp, "genie@example.com")
        gp.wait_for_url(re.compile(r"/genie$")); gp.wait_for_load_state("networkidle")
        ok(has(gp, "New requests"), "Genie dashboard lists new requests")
        ok(gp.locator("button[aria-label*='unread']").count() == 1, "bell shows unread notification count")
        shot(gp, "desktop-genie-dashboard")
        gp.goto(BASE + f"/genie/bookings/{booking_id}"); gp.wait_for_load_state("networkidle")
        ok(has(gp, "full address and phone number appear once you accept"), "address is masked before accepting")
        ok(gp.locator("pg-console-shell a[aria-current=page]").first.inner_text().strip().startswith("Jobs"), "sidebar highlights Jobs on a job page")
        gp.get_by_role("button", name="Accept job").click()
        gp.wait_for_timeout(1200)
        ok(has(gp, "Start the job"), "after accept the Genie sees the start-code form")
        ok(has(gp, "Shivalik"), "full address visible after accepting")

        # ------------------------------------------------------------ customer start code
        print("CUSTOMER start code")
        cp.reload(); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Confirmed"), "customer sees Confirmed")
        cp.get_by_role("button", name="Show start code").click()
        cp.wait_for_timeout(800)
        cp.wait_for_selector("p[aria-live=polite].font-display")
        code = re.search(r"(\d{4})", cp.locator("p[aria-live=polite].font-display").inner_text()).group(1)
        ok(len(code) == 4, f"customer got a 4-digit start code ({code})")
        shot(cp, "phone-start-code", full=False)

        # ------------------------------------------------------------ Genie starts (one wrong try) and completes
        print("GENIE start + complete")
        gp.fill("#otp", "0000" if code != "0000" else "1111")
        gp.get_by_role("button", name="Start job").click(); gp.wait_for_timeout(800)
        ok(has(gp, "not correct"), "wrong code is rejected with a clear message")
        gp.fill("#otp", code)
        gp.get_by_role("button", name="Start job").click(); gp.wait_for_timeout(1200)
        ok(has(gp, "Finish the job"), "job in progress after the right code")
        gp.get_by_role("button", name="Mark as completed").click()
        gp.fill("#extra", "120"); gp.fill("#extraNote", "1 fan capacitor")
        shot(gp, "desktop-genie-complete-dialog", full=False)
        gp.locator("dialog[open] form button[type=submit]").click(); gp.wait_for_timeout(1500)
        ok(has(gp, "Completed"), "job completed")
        shot(gp, "desktop-genie-job-completed")

        # ------------------------------------------------------------ customer pays online + reviews
        print("CUSTOMER pay + review")
        cp.reload(); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Payment of"), "customer is asked to pay after completion")
        cp.get_by_role("button", name=re.compile(r"^Pay ₹")).first.click()
        cp.wait_for_selector("text=Test checkout")
        shot(cp, "phone-payment-dialog", full=False)
        cp.get_by_role("button", name="Simulate a declined payment").click(); cp.wait_for_timeout(1000)
        ok(has(cp, "Payment failed"), "declined payment shows an error and allows retry")
        cp.locator("pg-payment-dialog button.btn-gold").click()
        ok(has(cp, "and paid", timeout=12000), "payment succeeds and booking shows paid")
        cp.get_by_role("radio", name="5 stars").click()
        cp.fill("#review-comment", "Quick, tidy and friendly. Fans work perfectly!")
        cp.get_by_role("button", name="Post review").click(); cp.wait_for_timeout(1200)
        ok(has(cp, "How did", absent=True), "review form disappears after posting")
        shot(cp, "phone-booking-completed")

        print("CUSTOMER receipt + report a problem")
        cp.reload(); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Receipts"), "paid booking lists a receipt")
        name, size = download(cp, lambda: cp.locator("button[aria-label^='Download ']").first.click())
        ok(name.endswith(".pdf") and size > 1000, f"receipt PDF downloads ({name}, {size} bytes)")
        cp.get_by_role("button", name=re.compile("Report a problem")).click()
        cp.get_by_label("Poor or incomplete work").check()
        cp.fill("#rp-subject", "One fan wobbles at full speed")
        cp.fill("#rp-desc", "The second fan wobbles and makes a clicking sound at speed 5. Please send someone to fix it or refund part of it.")
        shot(cp, "phone-report-dialog", full=False)
        cp.locator("dialog[open] button[type=submit]").click()
        cp.wait_for_url(re.compile(r"/account/tickets/[0-9a-f-]{36}"), timeout=15000); cp.wait_for_load_state("networkidle")
        ticket_url = cp.url
        ticket_id = ticket_url.rsplit("/", 1)[1]
        ok(has(cp, "One fan wobbles"), "report opens as a ticket thread")
        cp.set_input_files("pg-ticket-thread input[type=file]", files=[{"name": "fan.png", "mimeType": "image/png",
            "buffer": bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")}])
        cp.wait_for_selector("pg-ticket-thread img", timeout=10000)
        ok(cp.locator("pg-ticket-thread img").count() == 1, "photo attached to the report and shown")
        overflow(cp, "phone ticket")
        shot(cp, "phone-ticket-open")
        cp.goto(BASE + "/account/bookings"); cp.wait_for_load_state("networkidle")
        cp.get_by_role("tab", name="Past").click(); cp.wait_for_timeout(600)
        ok(has(cp, "Fan installation"), "booking listed under Past")
        shot(cp, "phone-bookings-list")
        overflow(cp, "phone bookings list")
        cp.goto(BASE + "/account/favourites"); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Ravi Patel"), "favourite Genie saved")
        cp.goto(BASE + "/account/addresses"); cp.wait_for_load_state("networkidle")
        cp.get_by_role("button", name="Add address").first.click()
        cp.wait_for_selector(".leaflet-container")
        cp.locator(".leaflet-container").click(position={"x": 150, "y": 120})
        cp.fill("#a-line1", "12, Sunrise Park"); cp.fill("#a-area", "Bodakdev"); cp.fill("#a-pin", "380054")
        cp.get_by_role("button", name="Work", exact=True).click()
        shot(cp, "phone-address-form", full=False)
        cp.get_by_role("button", name="Save address").click(); cp.wait_for_timeout(1200)
        ok(has(cp, "Sunrise Park"), "new address saved with a map pin")
        cp.goto(BASE + "/notifications"); cp.wait_for_load_state("networkidle")
        ok(has(cp, "accepted"), "notifications page lists booking updates")
        shot(cp, "phone-notifications")
        cp.goto(BASE + "/services?q=fan"); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Results for") and has(cp, "Fan installation"), "service search returns results")
        overflow(cp, "phone search")

        # Genie reply + wallet
        print("GENIE reviews + wallet")
        gp.goto(BASE + "/genie/reviews"); gp.wait_for_load_state("networkidle")
        gp.get_by_role("button", name="Reply").first.click()
        gp.locator("textarea").first.fill("Thank you! Happy to help anytime.")
        gp.get_by_role("button", name="Post reply").click(); gp.wait_for_timeout(1000)
        ok(has(gp, "Your reply"), "Genie replied to the review")
        gp.goto(BASE + "/genie/wallet"); gp.wait_for_load_state("networkidle")
        ok(has(gp, "Job earnings"), "wallet shows ledger entries")
        shot(gp, "desktop-genie-wallet")
        gp.goto(BASE + "/genie/availability"); gp.wait_for_load_state("networkidle")
        shot(gp, "desktop-genie-availability")

        # ------------------------------------------------------------ new Genie onboarding
        print("NEW GENIE onboarding (phone)")
        nctx = browser.new_context(**phone)
        np_ = nctx.new_page(); watch(np_, "new-genie"); PAGES.append(np_)
        phone_no = "9" + str(int(time.time()))[-9:]
        np_.goto(BASE + "/register?role=GENIE"); np_.wait_for_load_state("networkidle")
        np_.fill("#fullName", "Kiran Shah")
        np_.fill("#phone", phone_no)
        np_.fill("#password", PW)
        np_.locator("input[formcontrolname=acceptTerms]").check()
        np_.locator("input[formcontrolname=acceptGenieAgreement]").check()
        shot(np_, "phone-register-consent")
        sms_to = f"{phone_no}@sms.progenie.local"
        np_.get_by_role("button", name=re.compile("create|sign up", re.I)).last.click()
        ok(has(np_, "Verify your mobile", timeout=12000), "after sign-up the new Genie is asked to verify the mobile")
        code = mail_code(sms_to)
        ok(code is not None, "verification code arrived by SMS (Mailpit)")
        np_.fill("#otp-code", code or "000000")
        np_.wait_for_url(re.compile(r"/genie"), timeout=15000); np_.wait_for_load_state("networkidle")
        ok(not has(np_, "Verify now", timeout=1500), "no verify-phone banner once verified")
        ok(has(np_, "Finish your profile"), "new Genie sees the get-verified checklist")
        shot(np_, "phone-new-genie-dashboard")
        np_.goto(BASE + "/genie/onboarding"); np_.wait_for_load_state("networkidle")
        np_.fill("#bio", "Ten years of plumbing work across Ahmedabad. Clean, quick and fairly priced.")
        np_.fill("#exp", "10")
        np_.fill("#upi", "kiran.shah@okaxis")
        np_.wait_for_selector("#profile .leaflet-container")
        np_.locator("#profile .leaflet-container").click(position={"x": 180, "y": 130})
        np_.fill("#area", "Paldi")
        np_.get_by_role("button", name="Save profile").click(); np_.wait_for_timeout(1200)
        np_.select_option("#cat", label="Plumber"); np_.wait_for_timeout(800)
        np_.locator("#services ul button").first.click()
        np_.locator("#services ul button").nth(1).click()
        np_.get_by_role("button", name="Save services").click(); np_.wait_for_timeout(1200)
        np_.locator("#availability input[type=checkbox]").first.check()
        np_.get_by_role("button", name=re.compile("Copy to")).click()
        np_.get_by_role("button", name="Save hours").click(); np_.wait_for_timeout(1200)
        # tiny valid files
        png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")
        pdf = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF"
        np_.select_option("#doctype", label="Aadhaar card"); np_.fill("#last4", "4821")
        np_.set_input_files("#file", files=[{"name": "aadhaar.pdf", "mimeType": "application/pdf", "buffer": pdf}])
        np_.locator("#documents button[type=submit]").click(); np_.wait_for_timeout(1200)
        np_.select_option("#doctype", label="Selfie photo")
        np_.set_input_files("#file", files=[{"name": "selfie.png", "mimeType": "image/png", "buffer": png}])
        np_.locator("#documents button[type=submit]").click(); np_.wait_for_timeout(1500)
        overflow(np_, "phone onboarding")
        shot(np_, "phone-onboarding-full")
        submit = np_.get_by_role("button", name="Submit for review")
        ok(submit.is_enabled(), "all onboarding steps complete, submit enabled")
        submit.click(); np_.wait_for_url(re.compile(r"/genie$")); np_.wait_for_timeout(800)
        ok(has(np_, "Under review"), "after submitting the Genie is under review")

        # ------------------------------------------------------------ admin
        print("ADMIN (desktop + tablet)")
        actx = browser.new_context(**desktop)
        ap = actx.new_page(); watch(ap, "admin"); PAGES.append(ap)
        login(ap, "admin@progenie.in")
        ap.wait_for_url(re.compile(r"/admin$")); ap.wait_for_load_state("networkidle")
        ap.wait_for_selector("pg-column-chart svg")
        ok(has(ap, "GMV"), "admin overview KPIs render")
        ap.locator("pg-column-chart g[tabindex]").nth(20).hover(); ap.wait_for_timeout(300)
        ok(ap.locator("pg-column-chart [role=status]").count() >= 1, "chart tooltip on hover")
        shot(ap, "desktop-admin-overview")
        ap.goto(BASE + "/admin/genies"); ap.wait_for_load_state("networkidle")
        ap.get_by_text("Kiran Shah").click(); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Documents"), "admin opens the Genie review page")
        ap.get_by_role("button", name=re.compile("^Approve Aadhaar", re.I)).click(); ap.wait_for_timeout(800)
        ap.locator("aside").get_by_role("button", name="Approve").click()
        ap.locator("dialog[open] form button[type=submit]").click(); ap.wait_for_timeout(1500)
        ok(has(ap, "Approved"), "admin approved the new Genie")
        shot(ap, "desktop-admin-genie-review")
        ap.goto(BASE + "/admin/bookings"); ap.wait_for_load_state("networkidle")
        ap.locator("pg-booking-row").first.click(); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Timeline"), "admin booking detail with timeline")
        ap.goto(BASE + "/admin/finance"); ap.wait_for_load_state("networkidle")
        shot(ap, "desktop-admin-finance")
        ap.goto(BASE + "/admin/catalog"); ap.wait_for_load_state("networkidle")
        ap.get_by_role("tab", name="City pricing").click(); ap.wait_for_timeout(700)
        ok(ap.locator("input[id^=cr-]").first.input_value() in ("5", "5.0"), "commission shown as a percentage (5)")
        shot(ap, "desktop-admin-pricing")
        ap.goto(BASE + "/admin/users"); ap.wait_for_load_state("networkidle")
        ap.goto(BASE + "/admin/inbox"); ap.wait_for_load_state("networkidle")

        print("ADMIN complaint → split partial refund")
        ap.goto(BASE + "/admin/tickets"); ap.wait_for_load_state("networkidle")
        ok(has(ap, "One fan wobbles"), "the report is in the admin complaints queue")
        ok(ap.locator("pg-console-shell a[href='/admin/tickets']").first.inner_text().strip().endswith("1"), "Complaints badge counts open reports")
        shot(ap, "desktop-admin-tickets")
        ap.get_by_text("One fan wobbles").click(); ap.wait_for_load_state("networkidle")
        ap.get_by_role("button", name="Take it").click(); ap.wait_for_timeout(800)
        ok(has(ap, "With ProGenie") or has(ap, "With "), "admin took the complaint")
        ap.locator("input[name=internal]").check()
        ap.fill("#admin-reply", "Checked the photo, wobble is visible. Partial refund, Genie carries most of it.")
        ap.get_by_role("button", name="Save note").click()
        ok(has(ap, "Checked the photo"), "internal note saved")
        ap.locator("input[name=internal]").uncheck()
        ap.fill("#admin-reply", "Sorry about the fan. We are refunding part of the bill.")
        ap.get_by_role("button", name="Send reply").click()
        has(ap, "Sorry about the fan")
        ok(has(ap, "Internal note"), "internal note is marked in the thread")
        ap.get_by_role("button", name="Resolve…").click()
        ap.locator("dialog[open]").get_by_text("Refund", exact=True).click()
        ap.locator("dialog[open]").get_by_text("Warn the Genie").click()
        ap.locator("dialog[open] input[id$=-amount]").fill("200")
        ap.locator("dialog[open]").get_by_text("Split", exact=True).click()
        ap.locator("dialog[open] input[id$=-share]").fill("150")
        ap.fill("#r-note", "Refunded ₹200 for the wobbling fan. The Genie has been warned.")
        shot(ap, "desktop-admin-resolve-dialog", full=False)
        ap.locator("dialog[open] button[type=submit]").click(); ap.wait_for_timeout(2000)
        ok(has(ap, "How we resolved it"), "complaint resolved with refund and warning")
        ap.goto(BASE + f"/admin/bookings/{booking_id}"); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Genie ₹150.00") and has(ap, "ProGenie ₹50.00"), "admin booking page shows the refund split")
        ok(has(ap, "Credit note", timeout=10000), "credit note listed on the admin booking page")
        shot(ap, "desktop-admin-booking-money")

        print("CUSTOMER accepts the resolution")
        cp.goto(ticket_url); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Sorry about the fan") and not has(cp, "Checked the photo", timeout=500), "customer sees the reply but not the internal note")
        cp.get_by_role("button", name=re.compile("happy with this")).click(); cp.wait_for_timeout(1000)
        ok(has(cp, "Closed"), "customer accepted and the report is closed")
        cp.goto(booking_url); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Partly refunded") and has(cp, "Credit note"), "booking shows the partial refund and credit note")
        shot(cp, "phone-booking-refunded")

        print("CUSTOMER settings & privacy")
        cp.goto(BASE + "/account/privacy"); cp.wait_for_load_state("networkidle")
        cp.wait_for_selector("tbody input[type=checkbox]")
        cp.locator("tbody input[type=checkbox]").last.click()
        cp.get_by_role("button", name="Save preferences").click(); cp.wait_for_timeout(800)
        ok(has(cp, "saved"), "notification preferences saved")
        cp.get_by_role("button", name="Request a copy").click()
        cp.wait_for_selector("section button:has-text('Download')", timeout=20000)
        name, size = download(cp, lambda: cp.locator("section button:has-text('Download')").first.click())
        ok(name.endswith(".zip") and size > 200, f"data export downloads ({name}, {size} bytes)")
        overflow(cp, "phone settings & privacy")
        shot(cp, "phone-settings-privacy")

        print("OTP login (desktop)")
        octx = browser.new_context(**desktop)
        op = octx.new_page(); watch(op, "otp-login"); PAGES.append(op)
        before = mail_ids("customer@example.com")
        op.goto(BASE + "/login"); op.get_by_role("tab", name="One-time code").click()
        op.fill("#code-identifier", "customer@example.com")
        op.get_by_role("button", name="Send code").click()
        code = mail_code("customer@example.com", set(before))
        ok(code is not None, "login code arrived by email")
        op.fill("#otp-code", code or "000000")
        op.wait_for_url(lambda u: "/login" not in u, timeout=15000); op.wait_for_load_state("networkidle")
        op.goto(BASE + "/account/bookings"); op.wait_for_load_state("networkidle")
        ok("/account/bookings" in op.url and has(op, "My bookings"), "logged in with a one-time code (bookings open)")
        octx.close()

        print("ADMIN messages, deletions, policies")
        ap.goto(BASE + "/admin/messages"); ap.wait_for_load_state("networkidle")
        ap.get_by_role("tab", name="Sent").click(); ap.wait_for_timeout(800)
        ok(ap.locator("pg-status").count() > 0, "sent messages are listed")
        shot(ap, "desktop-admin-messages")
        ap.goto(BASE + "/admin/deletions"); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Account deletions"), "deletion requests page loads")
        ap.goto(BASE + "/admin/legal"); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Version 1 in force"), "policies list shows the versions in force")
        ap.goto(BASE + "/admin/legal/privacy"); ap.wait_for_load_state("networkidle")
        ap.wait_for_selector("textarea[name=body]")
        ap.locator("textarea[name=body]").press("Control+End")
        ap.locator("textarea[name=body]").type("\n\n## Photos in complaints\n\nPhotos you add to a report are kept for **2 years**, then deleted.")
        ok(ap.locator("pg-markdown h2", has_text="Photos in complaints").count() == 1, "live Markdown preview updates")
        ap.fill("#l-sum", "Added how long complaint photos are kept")
        ap.locator("input[name=re]").check()
        shot(ap, "desktop-admin-legal-editor")
        ap.get_by_role("button", name="Publish…").click()
        ap.locator("dialog[open]").get_by_role("button", name="Publish", exact=True).click()
        ap.wait_for_url(re.compile(r"/admin/legal$"), timeout=15000); ap.wait_for_load_state("networkidle")
        ok(has(ap, "Version 2 in force"), "Privacy Policy v2 published")

        print("CUSTOMER must accept the new policy")
        cp.goto(BASE + "/account/bookings"); cp.wait_for_load_state("networkidle")
        cp.reload(); cp.wait_for_load_state("networkidle")
        ok(has(cp, "We've updated our policies"), "consent dialog appears after a policy change")
        shot(cp, "phone-consent-dialog", full=False)
        cp.locator("dialog[open] input[name=agree]").check()
        cp.get_by_role("button", name="Accept and continue").click(); cp.wait_for_timeout(1200)
        ok(not has(cp, "We've updated our policies", timeout=1500), "consent dialog closes after accepting")
        cp.goto(BASE + "/legal/privacy"); cp.wait_for_load_state("networkidle")
        ok(has(cp, "Photos in complaints") and has(cp, "Earlier versions"), "public policy page shows v2 and older versions")
        overflow(cp, "phone legal page")
        shot(cp, "phone-legal-privacy")
        ap.goto(BASE + "/admin/legal/refunds"); ap.wait_for_load_state("networkidle")
        ok(ap.locator("input[name=re]").count() == 0 and has(ap, "part of the Terms"), "refund policy editor explains it is accepted via the Terms")

        # tablet sweep for overflow on key pages
        tctx = browser.new_context(viewport={"width": 768, "height": 1024}, **IN)
        tp = tctx.new_page(); watch(tp, "tablet-admin")
        login(tp, "admin@progenie.in")
        for path in ["/admin", "/admin/genies", "/admin/bookings", "/admin/users", "/admin/catalog", "/admin/finance", "/admin/inbox",
                     "/admin/tickets", f"/admin/tickets/{ticket_id}", f"/admin/bookings/{booking_id}", "/admin/messages", "/admin/legal",
                     "/admin/legal/privacy", "/admin/deletions", "/admin/settings"]:
            tp.goto(BASE + path); tp.wait_for_load_state("networkidle"); tp.wait_for_timeout(300)
            overflow(tp, f"tablet {path}")
        shot(tp, "tablet-admin-overview")

        # narrow phone (360) sweep for customer + genie pages
        sctx = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, has_touch=True, **IN)
        sp = sctx.new_page(); watch(sp, "360-customer")
        login(sp, "customer@example.com")
        for path in ["/", "/services", "/services/electrician", "/genies/1af855e6-31a8-5caa-bffa-6f864b9644ff", "/book/1af855e6-31a8-5caa-bffa-6f864b9644ff",
                     "/account/bookings", booking_url.replace(BASE, ""), "/account/addresses", "/account/favourites", "/account/profile", "/notifications",
                     "/account/tickets", ticket_url.replace(BASE, ""), "/account/privacy", "/legal/terms", "/legal/privacy"]:
            sp.goto(BASE + path); sp.wait_for_load_state("networkidle"); sp.wait_for_timeout(400)
            overflow(sp, f"360 {path}")
        shot(sp, "360-booking-detail")
        s2 = sctx.new_page(); watch(s2, "360-genie")
        sctx.clear_cookies()
        login(s2, "genie@example.com")
        for path in ["/genie", "/genie/jobs", f"/genie/bookings/{booking_id}", "/genie/onboarding", "/genie/availability", "/genie/wallet", "/genie/reviews",
                     "/genie/tickets", f"/genie/tickets/{ticket_id}", "/genie/privacy"]:
            s2.goto(BASE + path); s2.wait_for_load_state("networkidle"); s2.wait_for_timeout(400)
            overflow(s2, f"360 {path}")
        shot(s2, "360-genie-dashboard")
        # guest pages at 360
        gctx2 = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, has_touch=True, **IN)
        g2 = gctx2.new_page(); watch(g2, "360-guest")
        for path in ["/login", "/forgot-password", "/register", "/register?role=GENIE", "/legal/genie-agreement"]:
            g2.goto(BASE + path); g2.wait_for_load_state("networkidle"); g2.wait_for_timeout(300)
            overflow(g2, f"360 {path}")
        g2.goto(BASE + "/login"); g2.get_by_role("tab", name="One-time code").click(); g2.wait_for_timeout(200)
        shot(g2, "360-login-code")
    except Exception as e:
        dump()
        problems.append(f"CRASH: {str(e)[:400]}")
    try:
        browser.close()
    except Exception:
        pass

print(f"\n{checks} checks, {len(problems)} problems")
for pr in problems:
    print(" -", pr)
sys.exit(1 if problems else 0)
