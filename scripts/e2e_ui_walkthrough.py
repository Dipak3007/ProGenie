"""
ProGenie end-to-end UI walkthrough in a real browser (Playwright + Chromium).

Customer books on a phone → Genie accepts → customer shows the start code → Genie starts and completes with extras
→ customer pays online (a declined try first) and reviews → Genie replies → a new Genie signs up, completes the
get-verified wizard with KYC uploads and is approved by the admin → admin, tablet and 360 px sweeps.
Phase 2A: sign-up consent and phone verification (code read from Mailpit), receipt download, report a problem →
admin takes, replies and resolves with a split partial refund → customer accepts and gets a credit note,
one-time-code login, notification preferences, data export download, and publishing a policy that everyone must
accept again (the consent dialog).
Phase 3: job photos, statement, badges, coupons, live "on the way" tracking and Genie Now (the offer pops up on the
Genie's open console and the customer's search turns into the booking).
Every page is checked for horizontal overflow and console errors; screenshots land in scripts/ui-shots/.

Needs: Mailpit on http://localhost:8025 (docker compose), the API started with relaxed timing (see docs/LOCAL_SETUP.md, step 12) on a freshly reset database,
the web app on http://localhost:4200 (npm start), and `pip install playwright && python3 -m playwright install chromium`.
Usage: python3 scripts/e2e_ui_walkthrough.py        (exit code 0 = all checks passed)
"""
import os, re, sys, json, time, urllib.error, urllib.parse, urllib.request
from playwright.sync_api import sync_playwright, expect

BASE = "http://localhost:4200"
BASE = os.environ.get("PROGENIE_WEB", BASE)
MAILPIT = os.environ.get("MAILPIT", "http://localhost:8025").rstrip("/")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui-shots")
os.makedirs(OUT, exist_ok=True)
PW = "ProGenie@123"
problems = []
checks = 0
INFLIGHT = {}


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
    # Requests still running, minus the live-update stream (it stays open for the whole visit)
    inflight = set()
    INFLIGHT[id(page)] = inflight
    page.on("request", lambda r: "/api/v1/stream" not in r.url and inflight.add(r))
    page.on("requestfinished", lambda r: inflight.discard(r))
    page.on("requestfailed", lambda r: inflight.discard(r))


def settle(page, quiet_ms=500, timeout_ms=20000):
    """Like wait_for_load_state("networkidle"), which never fires while the live stream is open."""
    page.wait_for_load_state("load")
    inflight = INFLIGHT.get(id(page), set())
    end = time.time() + timeout_ms / 1000
    calm_since = None
    while time.time() < end:
        if not inflight:
            calm_since = calm_since or time.time()
            if (time.time() - calm_since) * 1000 >= quiet_ms:
                return
        else:
            calm_since = None
        page.wait_for_timeout(100)


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
    page.goto(BASE + "/login"); settle(page)
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
            page.reload(); settle(page)
    settle(page)


import atexit
PAGES = []

def api(method, path, body=None, token=None):
    """Direct API call for test set-up (through the web app's /api proxy)."""
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            raw = res.read()
            return res.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None


def api_token(who):
    return api("POST", "/api/v1/auth/login", {"identifier": who, "password": PW})[1]["accessToken"]

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
        cp.goto(BASE + "/services/electrician"); settle(cp)
        cp.get_by_role("button", name=re.compile("Fan installation")).first.click()
        cp.wait_for_timeout(600)
        ok(has(cp, "Offering"), "category page filters Genies by service")
        ok(cp.locator("select option[value=RATING]").first.inner_text().strip() == "Recommended", "default Genie order is called Recommended")
        cp.goto(BASE + "/genies/1af855e6-31a8-5caa-bffa-6f864b9644ff"); settle(cp)
        cp.get_by_role("button", name=re.compile("Fan installation")).click()
        cp.wait_for_selector("h1 button[aria-pressed]")
        if cp.locator("h1 button[aria-label='Save to favourites']").count():
            cp.locator("h1 button[aria-label='Save to favourites']").click()
        cp.wait_for_timeout(500)
        cp.get_by_role("button", name="Choose a time").click()
        cp.wait_for_url(re.compile(r"/book/")); settle(cp)
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
        cp.get_by_role("button", name="₹50", exact=True).click()
        cp.fill("#notes", "Two ceiling fans. Ladder available.")
        cp.wait_for_timeout(700)
        body = cp.inner_text("body")
        ok("Total" in body and "Travel" in body, "live quote shows travel fee and total")
        # Phase 3B: public coupons listed with why they don't apply to this customer/order; a typed code is checked
        cp.wait_for_selector("pg-coupon-picker ul button", timeout=10000)
        ok(has(cp, "WELCOME50") and has(cp, "first booking only"), "checkout lists public coupons and says why one can't be used")
        cp.fill("#coupon-code", "genie100")
        cp.locator("pg-coupon-picker form button[type=submit]").click()
        ok(has(cp, "at least ₹399"), "a typed private code that doesn't fit the order is refused with the reason")
        overflow(cp, "phone checkout")
        shot(cp, "phone-checkout-full")

        # The Genie is already looking at their dashboard when the request is sent (2B exit gate: live updates)
        print("GENIE waits on the dashboard (live updates)")
        gctx = browser.new_context(**desktop)
        gp = gctx.new_page(); watch(gp, "genie"); PAGES.append(gp)
        login(gp, "genie@example.com")
        gp.wait_for_url(re.compile(r"/genie$")); settle(gp)
        gp.wait_for_timeout(1500)  # the live stream connects after login

        cp.locator("div.sticky button.btn-gold").click()
        cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000)
        booking_url = cp.url
        booking_id = booking_url.rsplit("/", 1)[1]
        settle(cp)
        ok(has(cp, "Waiting for"), "new booking shows 'Waiting for … to accept'")
        shot(cp, "phone-booking-requested")
        overflow(cp, "phone booking detail")

        # ------------------------------------------------------------ Genie accepts on desktop
        print("GENIE (desktop)")
        try:
            gp.wait_for_selector(f"a[href$='/genie/bookings/{booking_id}']", timeout=10000)
            appeared = True
        except Exception:
            appeared = False
        ok(appeared, "the new request appears on the Genie's open dashboard without a refresh (live stream)")
        ok(has(gp, "New requests"), "Genie dashboard lists new requests")
        ok(gp.locator("button[aria-label*='unread']").count() == 1, "bell shows unread notification count")
        shot(gp, "desktop-genie-dashboard")
        gp.goto(BASE + f"/genie/bookings/{booking_id}"); settle(gp)
        ok(has(gp, "full address and phone number appear once you accept"), "address is masked before accepting")
        ok(gp.locator("pg-console-shell a[aria-current=page]").first.inner_text().strip().startswith("Jobs"), "sidebar highlights Jobs on a job page")
        gp.get_by_role("button", name="Accept job").click()
        gp.wait_for_timeout(1200)
        ok(has(gp, "Start the job"), "after accept the Genie sees the start-code form")
        ok(has(gp, "Shivalik"), "full address visible after accepting")

        # ------------------------------------------------------------ customer start code
        print("CUSTOMER start code")
        ok(has(cp, "Confirmed", timeout=10000), "customer's open booking page turns Confirmed without a refresh (live stream)")

        # ------------------------------------------------------------ Phase 3C-10: the Genie sets off, the customer follows
        print("GENIE on the way (live tracking)")
        ok(has(gp, "Leaving for this job?"), "the Genie is offered Start travelling")
        gctx.grant_permissions(["geolocation"], origin=BASE)
        gctx.set_geolocation({"latitude": 23.0450, "longitude": 72.5500, "accuracy": 15})
        gp.get_by_role("button", name="Start travelling").click()
        gp.wait_for_selector("[data-testid=sharing]", timeout=10000)
        ok(has(gp, "last sent", timeout=15000), "the Genie's page shares the location while on the way")
        ok(has(cp, "is on the way", timeout=10000), "customer's page turns 'on the way' without a refresh")
        cp.wait_for_selector("[data-testid=eta]", timeout=30000)
        eta = cp.locator("[data-testid=eta]").inner_text()
        ok(re.search(r"About \d+ min away", eta) is not None, f"customer sees the arrival estimate ({eta.strip()})")
        cp.wait_for_function("document.querySelectorAll('pg-tracking-map .leaflet-marker-icon').length === 2", timeout=15000)
        ok(cp.locator("pg-tracking-map .leaflet-marker-icon").count() == 2, "the map shows the Genie and the customer's home")
        ok(has(cp, "No route history is kept"), "the privacy note is shown under the map")
        overflow(cp, "phone on the way")
        shot(cp, "phone-genie-on-the-way")
        shot(gp, "desktop-genie-on-the-way")
        cp.get_by_role("button", name="Show start code").click()
        cp.wait_for_timeout(800)
        cp.wait_for_selector("p[aria-live=polite].font-display:not([data-testid=eta])")
        code = re.search(r"(\d{4})", cp.locator("p[aria-live=polite].font-display:not([data-testid=eta])").inner_text()).group(1)
        ok(len(code) == 4, f"customer got a 4-digit start code ({code})")
        shot(cp, "phone-start-code", full=False)

        # ------------------------------------------------------------ Genie starts (one wrong try) and completes
        print("GENIE start + complete")
        gp.fill("#otp", "0000" if code != "0000" else "1111")
        gp.get_by_role("button", name="Start job").click(); gp.wait_for_timeout(800)
        ok(has(gp, "not correct"), "wrong code is rejected with a clear message")
        gp.fill("#otp", code)
        # Phase 3A-8: an optional before photo, picked before starting (a real JPEG: a screenshot of the page)
        photo = os.path.join(OUT, "job-photo.jpg")
        with open(photo, "wb") as f:
            f.write(gp.screenshot(type="jpeg", quality=70))
        gp.locator("pg-photo-picker input[type=file]").first.set_input_files(photo)
        ok(gp.locator("pg-photo-picker img").count() == 1, "before photo previewed before starting")
        gp.get_by_role("button", name="Start job").click(); gp.wait_for_timeout(1200)
        ok(has(gp, "Finish the job"), "job in progress after the right code")
        gp.wait_for_selector("pg-job-photos img", timeout=15000)
        ok(gp.locator("pg-job-photos img").count() == 1, "the before photo is uploaded once the job starts")
        gp.get_by_role("button", name="Mark as completed").click()
        gp.fill("#extra", "120"); gp.fill("#extraNote", "1 fan capacitor")
        gp.locator("dialog[open] pg-photo-picker input[type=file]").set_input_files(photo)
        shot(gp, "desktop-genie-complete-dialog", full=False)
        gp.locator("dialog[open] form button[type=submit]").click(); gp.wait_for_timeout(2500)
        ok(has(gp, "Completed"), "job completed")
        gp.wait_for_function("document.querySelectorAll('pg-job-photos img').length === 2", timeout=15000)
        ok(gp.locator("pg-job-photos img").count() == 2, "before and after photos on the completed job")
        shot(gp, "desktop-genie-job-completed")
        # Phase 3.5A: the Genie rates the customer right after the job (never shown to the customer)
        ok(has(gp, "How was it working for"), "right after completing, the Genie is asked to rate the customer")
        rate = gp.locator("[data-testid=rate-customer]")
        rate.get_by_role("radio", name="4 stars").click()
        rate.get_by_role("button", name="Great to work with").click()
        rate.get_by_role("button", name="Save rating").click()
        ok(has(gp, "You rated"), "the Genie's rating of the customer is saved and shown to them")

        # ------------------------------------------------------------ customer pays online + reviews
        print("CUSTOMER pay + review")
        cp.reload(); settle(cp)
        ok(has(cp, "Payment of"), "customer is asked to pay after completion")
        cp.get_by_role("button", name=re.compile(r"^Pay ₹")).first.click()
        cp.wait_for_selector("text=Test checkout")
        shot(cp, "phone-payment-dialog", full=False)
        cp.get_by_role("button", name="Simulate a declined payment").click(); cp.wait_for_timeout(1000)
        ok(has(cp, "Payment failed"), "declined payment shows an error and allows retry")
        cp.locator("pg-payment-dialog button.btn-gold").click()
        ok(has(cp, "and paid", timeout=12000), "payment succeeds and booking shows paid")
        ok(has(cp, "You rated", timeout=1500, absent=True), "the customer never sees the Genie's rating of them")
        cp.get_by_role("radio", name="5 stars").click()
        cp.fill("#review-comment", "Quick, tidy and friendly. Fans work perfectly!")
        # Phase 3.5A: a photo of the finished work with the review
        cp.locator("[data-testid=review-photo-add] input[type=file]").set_input_files(photo)
        ok(cp.locator("img[alt='Photo to post with your review']").count() == 1, "a review photo is previewed before posting")
        cp.get_by_role("button", name="Post review").click(); cp.wait_for_timeout(2500)
        ok(has(cp, "How did", absent=True), "review form disappears after posting")
        shot(cp, "phone-booking-completed")
        # Phase 3.5A: book the same Genie, service and address again
        ok(cp.locator("[data-testid=book-again]").count() == 1, "a completed booking offers Book again")
        cp.get_by_role("button", name="Book again").click()
        cp.wait_for_url(re.compile(r"/book/[0-9a-f-]{36}\?.*addressId="), timeout=10000); settle(cp)
        ok("items=" in cp.url and cp.locator("[data-testid=summary-services]").inner_text().strip().startswith("Fan installation"), "Book again opens checkout with the same services and address")
        cp.goto(booking_url); settle(cp)
        # the photo is public with the review on the Genie's profile
        profile = cp.locator("a[href^='/genies/']").first.get_attribute("href")
        cp.goto(BASE + profile); settle(cp)
        try:
            cp.wait_for_selector("pg-review-photos img", timeout=8000)
        except Exception:
            pass
        ok(cp.locator("pg-review-photos img").count() >= 1, "the review photo shows on the Genie's public profile")
        cp.goto(booking_url); settle(cp)

        print("CUSTOMER receipt + report a problem")
        cp.reload(); settle(cp)
        ok(has(cp, "Receipts"), "paid booking lists a receipt")
        ok(has(cp, "Job photos") and cp.locator("pg-job-photos img").count() == 2, "the customer sees the before/after photos")
        name, size = download(cp, lambda: cp.locator("button[aria-label^='Download ']").first.click())
        ok(name.endswith(".pdf") and size > 1000, f"receipt PDF downloads ({name}, {size} bytes)")
        cp.get_by_role("button", name=re.compile("Report a problem")).click()
        cp.get_by_label("Poor or incomplete work").check()
        ok(cp.locator("[data-testid=help-suggestions]").get_by_text("This answered my question").count() == 1
           or has(cp, "This answered my question"), "Report a problem suggests help answers before the form")
        cp.fill("#rp-subject", "One fan wobbles at full speed")
        cp.fill("#rp-desc", "The second fan wobbles and makes a clicking sound at speed 5. Please send someone to fix it or refund part of it.")
        shot(cp, "phone-report-dialog", full=False)
        cp.locator("dialog[open] button[type=submit]").click()
        cp.wait_for_url(re.compile(r"/account/tickets/[0-9a-f-]{36}"), timeout=15000); settle(cp)
        ticket_url = cp.url
        ticket_id = ticket_url.rsplit("/", 1)[1]
        ok(has(cp, "One fan wobbles"), "report opens as a ticket thread")
        cp.set_input_files("pg-ticket-thread input[type=file]", files=[{"name": "fan.png", "mimeType": "image/png",
            "buffer": bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")}])
        cp.wait_for_function("document.querySelectorAll('pg-ticket-thread img').length === 3", timeout=10000)
        ok(cp.locator("pg-ticket-thread pg-job-photos img").count() == 2, "the report shows the job's before/after photos")
        ok(cp.locator("pg-ticket-thread img").count() == 3, "photo attached to the report and shown")
        overflow(cp, "phone ticket")
        shot(cp, "phone-ticket-open")
        cp.goto(BASE + "/account/bookings"); settle(cp)
        cp.get_by_role("tab", name="Past").click(); cp.wait_for_timeout(600)
        ok(has(cp, "Fan installation"), "booking listed under Past")
        shot(cp, "phone-bookings-list")
        overflow(cp, "phone bookings list")
        cp.goto(BASE + "/account/favourites"); settle(cp)
        ok(has(cp, "Ravi Patel"), "favourite Genie saved")
        print("CUSTOMER city switcher, PIN code check and a PIN-code-driven address")
        cp.get_by_role("button", name="Open menu").click()
        menu = cp.locator("#mobile-menu")
        try:
            menu.get_by_text("Ahmedabad, Gujarat").first.wait_for(timeout=8000)   # the list renders after the menu opens
        except Exception:
            pass
        ok(menu.get_by_text("Ahmedabad, Gujarat").count() >= 1, "phone menu lists the live cities")
        menu.locator("input[name=pincode]").fill("400001"); menu.get_by_role("button", name="Check").click()
        ok(has(cp, "Not in 400001 yet"), "header PIN code check: 400001 is not served")
        # Phase 3.5B: ask to be told when we arrive there (the customer's number is filled in)
        menu.locator("[data-testid=notify-open]").click()
        ok(menu.locator("[data-testid=notify-form] input:not([type=checkbox])").input_value() != "", "Notify me fills in the customer's number")
        menu.locator("[data-testid=notify-form] input[type=checkbox]").check()
        menu.locator("[data-testid=notify-form]").get_by_role("button", name="Notify me").click()
        ok(has(cp, "once when ProGenie arrives in 400001"), "Notify me: signed up for 400001 (one message, then forgotten)")
        shot(cp, "phone-notify-me", full=False)
        menu.locator("input[name=pincode]").fill("380009"); menu.get_by_role("button", name="Check").click()
        ok(has(cp, "We serve 380009"), "header PIN code check: 380009 is served")
        shot(cp, "phone-city-switcher", full=False)
        cp.get_by_role("button", name="Close menu").click()
        cp.goto(BASE + "/account/addresses"); settle(cp)
        cp.get_by_role("button", name="Add address").first.click()
        cp.wait_for_selector(".leaflet-container")
        cp.fill("#a-pin", "400001")
        ok(has(cp, "We don't serve 400001 yet"), "address form refuses an unserved PIN code")
        ok(cp.get_by_role("button", name="Save address").is_disabled(), "cannot save with an unserved PIN code")
        cp.fill("#a-pin", "380054")
        ok(has(cp, "Bodakdev, Ahmedabad"), "the PIN code tells the city and area")
        ok(cp.input_value("#a-area") == "Bodakdev", "area filled in from the PIN code")
        ok(has(cp, "Pin moved to 380054"), "a typed PIN code moves the map pin there")
        ok(cp.locator(".leaflet-marker-icon").count() == 1, "the pin is on the map before tapping it")
        cp.context.grant_permissions(["geolocation"], origin=BASE)
        cp.context.set_geolocation({"latitude": 23.0291, "longitude": 72.5192})
        cp.get_by_role("button", name="Use my location").click()
        ok(has(cp, "PIN code 380015 filled in from the map"), "moving the pin fills in its PIN code")
        ok(cp.input_value("#a-pin") == "380015", "PIN code field updated from the pin")
        try:  # the area comes from a second lookup (PIN code -> area) right after the PIN code is filled in
            cp.wait_for_function("document.querySelector('#a-area')?.value === 'Satellite'", timeout=5000)
        except Exception:
            pass
        ok(cp.input_value("#a-area") == "Satellite", "area follows the PIN code found from the map pin")
        ok(cp.locator("#a-area").is_disabled(), "area is disabled (it comes from the PIN code)")
        cp.fill("#a-pin", "380054")
        ok(has(cp, "Pin moved to 380054"), "typing a PIN code again moves the pin back")
        cp.locator(".leaflet-container").click(position={"x": 150, "y": 120})
        ok(has(cp, "The pin is in 380054"), "a small adjustment keeps the PIN code")
        cp.fill("#a-line1", "12, Sunrise Park")
        cp.get_by_role("button", name="Work", exact=True).click()
        shot(cp, "phone-address-form", full=False)
        cp.get_by_role("button", name="Save address").click(); cp.wait_for_timeout(1200)
        ok(has(cp, "Sunrise Park"), "new address saved with a map pin")
        work = cp.locator("article", has_text="12, Sunrise Park").first
        ok("Bodakdev" in work.inner_text(), "the saved address shows the area of its PIN code")
        work.get_by_role("button", name="Edit").click()
        cp.wait_for_selector("dialog[open] #a-area")
        cp.wait_for_function("document.querySelector('dialog[open] #a-area')?.value === 'Bodakdev'", timeout=5000)
        cp.fill("dialog[open] #a-pin", "380015")
        try:
            cp.wait_for_function("document.querySelector('dialog[open] #a-area')?.value === 'Satellite'", timeout=5000)
        except Exception:
            pass
        ok(cp.input_value("dialog[open] #a-area") == "Satellite", "editing: the area follows a newly typed PIN code")
        cp.get_by_role("button", name="Save changes").click(); cp.wait_for_timeout(1200)
        work = cp.locator("article", has_text="12, Sunrise Park").first
        ok("Satellite" in work.inner_text() and "380015" in work.inner_text(), "edited address saved with the new PIN code's area")
        # Phase 3.5A: booking for someone else, the person who will be there
        work.get_by_role("button", name="Edit").click()
        cp.wait_for_selector("dialog[open] #a-contact-name")
        cp.fill("dialog[open] #a-contact-name", "Asha Shah")
        ok(has(cp, "Add both their name and mobile number"), "half a contact asks for the other half")
        cp.fill("dialog[open] #a-contact-phone", "98250 12345")
        cp.get_by_role("button", name="Save changes").click(); cp.wait_for_timeout(1200)
        work = cp.locator("article", has_text="12, Sunrise Park").first
        ok("For Asha Shah" in work.inner_text(), "the address shows who it's for")
        cp.goto(BASE + "/notifications"); settle(cp)
        ok(has(cp, "accepted"), "notifications page lists booking updates")
        shot(cp, "phone-notifications")
        cp.goto(BASE + "/services?q=fan"); settle(cp)
        ok(has(cp, "Results for") and has(cp, "Fan installation"), "service search returns results")
        overflow(cp, "phone search")

        # Genie reply + wallet
        print("GENIE reviews + wallet")
        gp.goto(BASE + "/genie/reviews"); settle(gp)
        gp.get_by_role("button", name="Reply").first.click()
        gp.locator("textarea").first.fill("Thank you! Happy to help anytime.")
        gp.get_by_role("button", name="Post reply").click(); gp.wait_for_timeout(1000)
        ok(has(gp, "Your reply"), "Genie replied to the review")
        ok(gp.locator("img[alt^='Customer photo']").count() >= 1, "the Genie sees the photo in the review")
        gp.get_by_role("button", name="Report").first.click()
        gp.wait_for_selector("dialog[open]")
        gp.locator("dialog[open] input, dialog[open] textarea").first.fill("Walkthrough: checking the report flow")
        gp.get_by_role("button", name="Report photo").click()
        ok(has(gp, "Reported"), "the Genie reports a review photo")
        gp.goto(BASE + "/genie/wallet"); settle(gp)
        ok(has(gp, "Job earnings"), "wallet shows ledger entries")
        ok(has(gp, "Monthly statement"), "wallet offers the monthly statement")
        name, size = download(gp, lambda: gp.get_by_role("button", name="PDF").click())
        ok(re.fullmatch(r"progenie-earnings-\d{4}-\d{2}\.pdf", name) is not None and size > 1500, f"statement PDF downloads ({name}, {size} bytes)")
        name, size = download(gp, lambda: gp.get_by_role("button", name=re.compile("^CSV")).click())
        ok(name.endswith(".csv") and size > 200, f"statement CSV downloads ({name}, {size} bytes)")
        shot(gp, "desktop-genie-wallet")
        gp.goto(BASE + "/genie"); settle(gp)
        ok(has(gp, "Your badges"), "the Genie dashboard explains badges")
        gp.goto(BASE + "/genie/availability"); settle(gp)
        shot(gp, "desktop-genie-availability")

        # ------------------------------------------------------------ Phase 3C-9: Genie Now
        print("GENIE NOW (customer on a phone, Genie on desktop)")
        g_token, a_token = api_token("genie@example.com"), api_token("admin@progenie.in")
        saved_hours = api("GET", "/api/v1/genie/profile", token=g_token)[1]["availability"]
        api("PUT", "/api/v1/genie/availability", {"windows": [{"dayOfWeek": d, "startTime": "00:00", "endTime": "23:45"} for d in range(1, 8)]}, g_token)
        city = next(c for c in api("GET", "/api/v1/admin/cities", token=a_token)[1] if c["active"])
        pricing = {k: city[k] for k in ("commissionRate", "travelFreeKm", "travelBaseFee", "travelPerKm", "travelFeeCap")}
        api("PUT", f"/api/v1/admin/cities/{city['cityId']}/pricing", {**pricing, "instantFee": 20}, a_token)
        gp.goto(BASE + "/genie"); settle(gp)
        if gp.get_by_role("switch", name=re.compile("Go online")).count():
            gp.get_by_role("switch", name=re.compile("Go online")).click(); gp.wait_for_timeout(800)
        elif gp.get_by_role("button", name="Go online").count():
            gp.get_by_role("button", name="Go online").click(); gp.wait_for_timeout(800)
        ok(has(gp, "You're online"), "the Genie is online (ProGenie open) and can get Genie Now jobs")
        ist = time.gmtime(time.time() + 5.5 * 3600)
        if ist.tm_hour * 60 + ist.tm_min > 22 * 60:  # same rule as the smoke test
            print("  (Genie Now skipped: after 22:00 in India no Genie Now job fits in a working day)")
        else:
            cp.goto(BASE + "/services/electrician"); settle(cp)
            cp.get_by_role("button", name=re.compile("Fan installation")).first.click()
            cp.wait_for_selector("[data-testid=genie-now-link]", timeout=8000)
            ok(cp.locator("[data-testid=genie-now-link]").count() == 1, "a Genie Now service offers 'Need it now?'")
            cp.locator("[data-testid=genie-now-link]").click()
            cp.wait_for_url(re.compile(r"/now/\d+"), timeout=10000); settle(cp)
            cp.wait_for_selector("[data-testid=instant-quote] .font-display", timeout=15000)
            quote_text = cp.locator("[data-testid=instant-quote]").inner_text()
            ok("free near you" in quote_text and "Genie Now fee" in quote_text, "the customer sees free Genies nearby and the price range")
            overflow(cp, "phone genie now")
            shot(cp, "phone-genie-now")
            cp.get_by_role("button", name="Find a Genie now").click()
            cp.wait_for_selector("[data-testid=instant-search]", timeout=10000)
            ok(has(cp, "Finding your Genie"), "the search screen asks one Genie at a time")
            shot(cp, "phone-genie-now-searching", full=False)
            try:
                gp.wait_for_selector("[data-testid=instant-offer]", timeout=15000)
                offered = True
            except Exception:
                offered = False
            ok(offered, "the offer pops up on the Genie's open console (live stream), with a countdown")
            if offered:
                ok("you earn" in gp.locator("[data-testid=instant-offer]").inner_text().lower(), "the offer shows what the Genie earns")
                shot(gp, "desktop-genie-now-offer", full=False)
                gp.locator("[data-testid=instant-offer]").get_by_role("button", name="Accept job").click()
                gp.wait_for_url(re.compile(r"/genie/bookings/[0-9a-f-]{36}"), timeout=15000); settle(gp)
                ok(has(gp, "Genie Now"), "the Genie lands on the accepted job, marked Genie Now")
                cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000); settle(cp)
                ok(has(cp, "within about an hour") and has(cp, "Genie Now fee"), "the customer's page opens the booking: arriving within an hour, with the fee")
                shot(cp, "phone-genie-now-booked")
                # free the Genie again for the rest of the walkthrough
                now_booking_id = cp.url.rsplit("/", 1)[1]
                api("POST", f"/api/v1/admin/bookings/{now_booking_id}/cancel", {"reason": "walkthrough", "waiveFee": True}, a_token)
        api("PUT", "/api/v1/genie/availability", {"windows": saved_hours}, g_token)
        api("PUT", f"/api/v1/admin/cities/{city['cityId']}/pricing", {**pricing, "instantFee": 0}, a_token)

        # ------------------------------------------------------------ new Genie onboarding
        print("NEW GENIE onboarding (phone)")
        nctx = browser.new_context(**phone)
        np_ = nctx.new_page(); watch(np_, "new-genie"); PAGES.append(np_)
        phone_no = "9" + str(int(time.time()))[-9:]
        np_.goto(BASE + "/register?role=GENIE"); settle(np_)
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
        np_.wait_for_url(re.compile(r"/genie"), timeout=15000); settle(np_)
        ok(not has(np_, "Verify now", timeout=1500), "no verify-phone banner once verified")
        ok(has(np_, "Finish your profile"), "new Genie sees the get-verified checklist")
        shot(np_, "phone-new-genie-dashboard")
        np_.goto(BASE + "/genie/onboarding"); settle(np_)
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
        ap.wait_for_url(re.compile(r"/admin$")); settle(ap)
        ap.wait_for_selector("pg-column-chart svg")
        ok(has(ap, "GMV"), "admin overview KPIs render")
        ap.locator("pg-column-chart g[tabindex]").nth(20).hover(); ap.wait_for_timeout(300)
        ok(ap.locator("pg-column-chart [role=status]").count() >= 1, "chart tooltip on hover")
        shot(ap, "desktop-admin-overview")
        ap.goto(BASE + "/admin/genies"); settle(ap)
        ap.get_by_text("Kiran Shah").click(); settle(ap)
        ok(has(ap, "Documents"), "admin opens the Genie review page")
        ap.get_by_role("button", name=re.compile("^Approve Aadhaar", re.I)).click(); ap.wait_for_timeout(800)
        ap.locator("aside").get_by_role("button", name="Approve").click()
        ap.locator("dialog[open] form button[type=submit]").click(); ap.wait_for_timeout(1500)
        ok(has(ap, "Approved"), "admin approved the new Genie")
        ok(has(ap, "Earnings statement"), "admin can download a Genie's earnings statement")
        shot(ap, "desktop-admin-genie-review")
        review_url = ap.url
        ap.goto(BASE + "/admin/genies"); settle(ap)
        ap.get_by_role("button", name="Recalculate badges").click()
        ok(has(ap, "Badges updated for"), "admin recalculates badges")
        ap.goto(review_url); settle(ap)
        ok(has(ap, "New") and ap.locator("pg-genie-badges li").count() >= 1, "the newly approved Genie has the New badge")
        ap.goto(BASE + "/admin/bookings"); settle(ap)
        ap.locator("pg-booking-row").first.click(); settle(ap)
        ok(has(ap, "Timeline"), "admin booking detail with timeline")
        ap.goto(BASE + "/admin/finance"); settle(ap)
        shot(ap, "desktop-admin-finance")
        ap.goto(BASE + "/admin/catalog"); settle(ap)
        ap.get_by_role("tab", name="Cities & pricing").click(); ap.wait_for_timeout(900)
        ok(ap.locator("input[id^=cr-]").first.input_value() in ("5", "5.0"), "commission shown as a percentage (5)")
        ok(has(ap, "20 active PIN codes"), "Ahmedabad shows its PIN codes")
        shot(ap, "desktop-admin-pricing")
        print("ADMIN opens a second city as data")
        city = f"Testpur {int(time.time()) % 100000}"
        ap.get_by_role("button", name="New city").click()
        ap.fill("#ct-name", city); ap.fill("#ct-state", "Gujarat")
        ap.wait_for_selector("dialog[open] .leaflet-container")
        ap.locator("dialog[open] .leaflet-container").click(position={"x": 200, "y": 140})
        ap.get_by_role("button", name="Create city (switched off)").click(); ap.wait_for_timeout(1500)
        card = ap.locator("pg-admin-cities section", has=ap.get_by_role("heading", name=f"{city}, Gujarat"))
        ok(card.count() == 1 and "Switched off" in card.inner_text(), "new city created, switched off")
        pin = "39" + str(int(time.time()) % 9000 + 1000)
        card.locator("input[name=pincode]").fill(pin); card.locator("input[name=area]").fill("Test Nagar")
        card.get_by_role("button", name="Add", exact=True).click(); ap.wait_for_timeout(1200)
        ok(pin in card.inner_text(), f"PIN code {pin} added to the new city")
        card.get_by_role("button", name="Switch on").click()
        ap.locator("dialog[open] form button[type=submit]").click(); ap.wait_for_timeout(1200)
        ok("Live" in card.inner_text(), "new city switched on")
        shot(ap, "desktop-admin-cities")
        overflow(ap, "admin cities")
        with urllib.request.urlopen(BASE + f"/api/v1/serviceability?pincode={pin}") as r:
            ok(json.load(r)["serviceable"], "its PIN code is served right away (no release)")
        card.get_by_role("button", name="Switch off").click()
        ap.locator("dialog[open] form button[type=submit]").click(); ap.wait_for_timeout(1200)
        ok("Switched off" in card.inner_text(), "and switched off again")
        ap.goto(BASE + "/admin/users"); settle(ap)
        ap.goto(BASE + "/admin/inbox"); settle(ap)

        print("ADMIN creates a coupon")
        coupon_code = "WALK" + str(int(time.time()))[-6:]
        ap.goto(BASE + "/admin/coupons"); settle(ap)
        ap.get_by_role("button", name="New coupon").click()
        ap.fill("#cp-code", coupon_code.lower())
        ap.fill("#cp-title", "Rs 25 off for testers")
        ap.fill("#cp-value", "25")
        shot(ap, "desktop-admin-coupon-dialog", full=False)
        ap.locator("dialog[open] form button[type=submit]").click()
        ok(has(ap, coupon_code + " created"), "admin creates a coupon from the Coupons page")
        ok(has(ap, coupon_code) and has(ap, "₹25 off"), "the new coupon is listed with its discount")
        shot(ap, "desktop-admin-coupons")

        # ------------------------------------------------------------ Phase 3D: demand heatmap
        print("ADMIN demand map")
        ap.goto(BASE + "/admin/demand"); settle(ap)
        ap.wait_for_selector("[data-testid=demand-top] li", timeout=10000)
        total = ap.locator("[data-testid=demand-total]").inner_text()
        ok(re.search(r"[1-9]\d* bookings", total) is not None, f"the demand map counts this period's bookings ({total.strip()})")
        ok(re.search(r"\d{6}", ap.locator("[data-testid=demand-top]").inner_text()) is not None, "top places are listed by PIN code")
        ap.wait_for_function("document.querySelectorAll('.leaflet-heatmap-layer, canvas.leaflet-heatmap-layer').length > 0", timeout=10000)
        ok(ap.locator("canvas.leaflet-heatmap-layer").count() == 1, "bookings are drawn as a heat layer")
        shot(ap, "desktop-admin-demand-bookings")
        ap.get_by_role("radio", name="Unmet demand").click(); ap.wait_for_timeout(1500)
        ok(has(ap, "What people couldn") and has(ap, "PIN code we don"), "the unmet layer shows the unserved PIN code typed in the address form")
        waiting = ap.locator("[data-testid=demand-waiting]")
        ok(waiting.count() == 1 and "400001" in waiting.inner_text(), "People waiting for us lists the PIN code from Notify me")
        overflow(ap, "admin demand map")
        shot(ap, "desktop-admin-demand-unmet")

        # ------------------------------------------------------------ Phase 3.5B: help centre editor
        print("ADMIN help centre")
        ap.goto(BASE + "/admin/help"); settle(ap)
        ap.wait_for_selector("[data-testid=admin-help-row]", timeout=10000)
        ok(ap.locator("[data-testid=admin-help-row]").count() >= 30 and has(ap, "For Genies"), "admin sees every help answer, grouped")
        ok(has(ap, "👍"), "admin sees how often each answer helped")
        ap.locator("[data-testid=admin-help-row]").first.get_by_role("button", name="Edit").click()
        ap.wait_for_selector("dialog[open] #hans")
        ap.locator("dialog[open] #hans").press("Control+End")
        ap.locator("dialog[open] #hans").type("\n\n**Walkthrough preview**")
        try:
            ap.locator("dialog[open] pg-markdown strong", has_text="Walkthrough preview").wait_for(timeout=5000)
        except Exception:
            pass
        ok(ap.locator("dialog[open] pg-markdown strong", has_text="Walkthrough preview").count() == 1, "the answer editor previews Markdown")
        shot(ap, "desktop-admin-help-editor", full=False)
        ap.keyboard.press("Escape"); ap.wait_for_timeout(400)
        overflow(ap, "admin help centre")

        # ------------------------------------------------------------ Phase 3.5A: review photos, customers to watch
        print("ADMIN review photos + customers that need attention")
        ap.goto(BASE + "/admin/review-photos"); settle(ap)
        card = ap.locator("[data-testid=admin-review-photo]", has_text="Walkthrough: checking the report flow").first
        ok(card.count() == 1, "the reported photo is in the admin queue with the Genie's reason")
        shot(ap, "desktop-admin-review-photos")
        card.get_by_role("button", name="Keep").click(); ap.wait_for_timeout(1000)
        ok(has(ap, "Photo kept"), "admin keeps the photo (the report is cleared)")
        ap.goto(BASE + "/admin/users"); settle(ap)
        ap.get_by_label("Needs attention").check(); ap.wait_for_timeout(1000)
        ok(ap.get_by_label("Needs attention").is_checked(), "admins can list customers who need attention")
        overflow(ap, "admin users")

        print("ADMIN complaint → split partial refund")
        ap.goto(BASE + "/admin/tickets"); settle(ap)
        ok(has(ap, "One fan wobbles"), "the report is in the admin complaints queue")
        ok(ap.locator("pg-console-shell a[href='/admin/tickets']").first.inner_text().strip().endswith("1"), "Complaints badge counts open reports")
        shot(ap, "desktop-admin-tickets")
        ap.get_by_text("One fan wobbles").click(); settle(ap)
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
        ap.goto(BASE + f"/admin/bookings/{booking_id}"); settle(ap)
        ok(has(ap, "Genie ₹150.00") and has(ap, "ProGenie ₹50.00"), "admin booking page shows the refund split")
        ok(has(ap, "Credit note", timeout=10000), "credit note listed on the admin booking page")
        shot(ap, "desktop-admin-booking-money")

        print("CUSTOMER accepts the resolution")
        cp.goto(ticket_url); settle(cp)
        ok(has(cp, "Sorry about the fan") and not has(cp, "Checked the photo", timeout=500), "customer sees the reply but not the internal note")
        cp.get_by_role("button", name=re.compile("happy with this")).click(); cp.wait_for_timeout(1000)
        ok(has(cp, "Closed"), "customer accepted and the report is closed")
        cp.goto(booking_url); settle(cp)
        ok(has(cp, "Partly refunded") and has(cp, "Credit note"), "booking shows the partial refund and credit note")
        shot(cp, "phone-booking-refunded")

        print("CUSTOMER books with a coupon")
        cp.goto(BASE + "/book/1af855e6-31a8-5caa-bffa-6f864b9644ff"); settle(cp)
        cp.wait_for_selector("pg-slot-picker button[aria-pressed]", timeout=15000)
        cp.locator("pg-slot-picker button[aria-pressed]").first.click()
        cp.wait_for_selector(f"pg-coupon-picker ul button:has-text('{coupon_code}')", timeout=10000)
        before = cp.locator("#summary dl").inner_text()
        cp.locator(f"pg-coupon-picker ul button:has-text('{coupon_code}')").click()
        ok(has(cp, coupon_code + " applied"), "tapping a public coupon applies it")
        cp.wait_for_timeout(400)
        summary = cp.locator("#summary dl").inner_text()
        ok(("Coupon " + coupon_code) in summary and "Coupon" not in before, "the summary shows the coupon discount")
        shot(cp, "phone-checkout-coupon", full=False)
        cp.locator("div.sticky button.btn-gold").click()
        cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000); settle(cp)
        ok(has(cp, "Coupon " + coupon_code), "the booking shows the coupon in its price breakdown")
        coupon_booking_url = cp.url
        cp.get_by_role("button", name="Cancel booking").first.click(); cp.wait_for_timeout(500)
        cp.get_by_role("button", name="Cancel booking").last.click(); cp.wait_for_timeout(1500)
        ok(has(cp, "Cancelled"), "the coupon booking is cancelled (the use is given back)")
        ap.goto(BASE + "/admin/coupons"); settle(ap)
        row = ap.locator("tr", has_text=coupon_code)
        ok("0" in row.locator("td").nth(3).inner_text(), "admin sees the use given back after the cancellation")

        # ------------------------------------------------------------ Phase 3.5C: several services in one booking
        print("CUSTOMER books two services in one visit, then drops one")
        cp.goto(BASE + "/genies/1af855e6-31a8-5caa-bffa-6f864b9644ff"); settle(cp)
        cp.get_by_role("button", name=re.compile("Fan installation")).first.click()
        cp.get_by_role("button", name=re.compile("Switchboard repair")).first.click()
        cp.wait_for_timeout(600)
        picked = cp.locator("[data-testid=picked-services]").inner_text()
        ok("Fan installation" in picked and "Switchboard repair" in picked, "the Genie profile lets the customer tick two services")
        cp.get_by_role("button", name="Choose a time").click()
        cp.wait_for_url(re.compile(r"/book/.*items=")); settle(cp)
        cp.locator("[aria-label='One more Fan installation']").click(); cp.wait_for_timeout(800)
        summary = cp.locator("[data-testid=summary-services]").inner_text()
        ok("Fan installation ×2" in summary and "Switchboard repair" in summary, "checkout carries both services and the quantity")
        ok(has(cp, "About 2 h") or has(cp, "About 1 h"), "checkout says how long the whole job takes")
        cp.wait_for_selector("pg-slot-picker button[aria-pressed]", timeout=15000)
        cp.locator("pg-slot-picker button[aria-pressed]").last.click()
        cp.wait_for_timeout(800)
        ok(cp.locator("#summary dl").get_by_text("Switchboard repair").count() >= 1, "the price lists each service")
        overflow(cp, "phone checkout with two services")
        shot(cp, "phone-checkout-two-services", full=False)
        cp.locator("div.sticky button.btn-gold").click()
        cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000); settle(cp)
        ok(cp.locator("[data-testid=price-line]").count() == 2, "the booking shows one price line per service")
        ok(has(cp, "Fan installation ×2 + Switchboard repair"), "the booking is named after both services")
        shot(cp, "phone-booking-two-services")
        cp.locator("[aria-label='Remove Switchboard repair from this booking']").click()
        cp.locator("dialog[open]").get_by_role("button", name="Remove service").click(); cp.wait_for_timeout(1500)
        ok(cp.locator("[data-testid=price-line]").count() == 1 and has(cp, "Switchboard repair removed"), "the customer drops a service before the Genie sets off")
        ok(has(cp, "Services changed"), "the timeline shows the change")
        cp.get_by_role("button", name="Cancel booking").first.click(); cp.wait_for_timeout(500)
        cp.get_by_role("button", name="Cancel booking").last.click(); cp.wait_for_timeout(1500)
        ok(has(cp, "Cancelled"), "the two-service booking is cancelled again")

        # ------------------------------------------------------------ Phase 3.5D: inspection visit and quote
        print("CUSTOMER books an inspection; the GENIE sends a quote; the CUSTOMER accepts it")
        gid = "1af855e6-31a8-5caa-bffa-6f864b9644ff"
        c_tok, g_tok, a_tok = api_token("customer@example.com"), api_token("genie@example.com"), api_token("admin@progenie.in")
        prof = api("GET", f"/api/v1/genies/{gid}")[1]
        wiring = next(x for x in prof["services"] if x["inspectionFirst"])
        cp.goto(BASE + f"/book/{gid}?items={wiring['serviceId']}:1"); settle(cp)
        ok(cp.locator("[data-testid=checkout-inspection]").count() == 1, "checkout says an inspection visit comes first")
        cp.wait_for_selector("pg-slot-picker button[aria-pressed]", timeout=15000)
        cp.locator("pg-slot-picker button[aria-pressed]").first.click(); cp.wait_for_timeout(800)
        ok(cp.locator("#summary dl").get_by_text("Inspection visit").count() >= 1 and "Book inspection" in cp.locator("div.sticky button.btn-gold").inner_text(),
           "checkout prices the inspection visit, not the job")
        overflow(cp, "phone checkout inspection")
        shot(cp, "phone-checkout-inspection", full=False)
        cp.locator("div.sticky button.btn-gold").click()
        cp.wait_for_url(re.compile(r"/account/bookings/[0-9a-f-]{36}"), timeout=15000); settle(cp)
        insp_id = cp.url.rstrip("/").split("/")[-1]
        ok(has(cp, "Inspection: House wiring check-up") and cp.locator("[data-testid=quote-panel]").count() == 1,
           "the inspection booking shows where the quote will appear")
        api("POST", f"/api/v1/genie/bookings/{insp_id}/accept", None, g_tok)
        code = api("POST", f"/api/v1/bookings/{insp_id}/start-code", None, c_tok)[1]["code"]
        api("POST", f"/api/v1/genie/bookings/{insp_id}/start", {"code": code}, g_tok)
        api("POST", f"/api/v1/genie/bookings/{insp_id}/complete", {"cashCollected": True}, g_tok)
        gp.goto(BASE + f"/genie/bookings/{insp_id}"); settle(gp)
        gp.wait_for_selector("[data-testid=quote-line]", timeout=10000)
        ok(gp.locator("[data-testid=quote-line]").count() == 1, "the Genie's quote starts from the service the customer asked about")
        gp.locator("[data-testid=quote-line] select").first.select_option(label="Fan installation"); gp.wait_for_timeout(300)
        gp.get_by_role("button", name="Add a line").click(); gp.wait_for_timeout(300)
        line2 = gp.locator("[data-testid=quote-line]").nth(1)
        line2.locator("input[maxlength='150']").fill("Replace 20 m of old wiring")
        line2.locator("input[type=number]").nth(1).fill("4500")
        gp.fill("#q-minutes", "180"); gp.fill("#q-note", "Materials included")
        gp.wait_for_timeout(300)
        ok("4,7" in gp.locator("[data-testid=quote-total]").inner_text(), "the quote total adds up the lines")
        shot(gp, "desktop-genie-quote-editor")
        gp.get_by_role("button", name="Send quote").click(); gp.wait_for_timeout(1500)
        ok(gp.locator("pg-quote-summary").count() == 1 and has(gp, "Change the quote"), "the Genie sends the quote and can still change it")
        cp.reload(); settle(cp)
        lines = cp.locator("[data-testid=quote-lines]").inner_text()
        ok("Fan installation" in lines and "Replace 20 m of old wiring" in lines and "Inspection fee already paid" in lines,
           "the customer sees the quote with the inspection fee taken off")
        shot(cp, "phone-quote-ready")
        cp.get_by_role("button", name="Accept and pick a time").click()
        cp.wait_for_selector("dialog[open] pg-slot-picker button[aria-pressed]", timeout=15000)
        cp.locator("dialog[open] pg-slot-picker button[aria-pressed]").first.click(); cp.wait_for_timeout(500)
        cp.locator("dialog[open]").get_by_role("button", name="Book the job").click()
        cp.wait_for_url(lambda u: insp_id not in u and "/account/bookings/" in u, timeout=15000); settle(cp)
        ok(cp.locator("[data-testid=price-line]").count() == 2 and cp.locator("[data-testid=inspection-credit]").count() == 1,
           "the job is booked from the quote: two lines, inspection fee taken off")
        ok(has(cp, "from the quote after"), "the job links back to the inspection")
        overflow(cp, "phone quote job")
        shot(cp, "phone-quote-job")
        job_id = cp.url.rstrip("/").split("/")[-1]
        ap.goto(BASE + f"/admin/bookings/{insp_id}"); settle(ap)
        ok(ap.locator("[data-testid=admin-quote]").count() == 1 and has(ap, "Accepted"), "admin sees the accepted quote on the inspection")
        api("POST", f"/api/v1/admin/bookings/{job_id}/cancel", {"reason": "walkthrough", "waiveFee": True}, a_tok)

        # ------------------------------------------------------------ Phase 3.5E: cart
        print("CUSTOMER leaves checkout and comes back to the cart")
        fan_id = next(x["serviceId"] for x in prof["services"] if x["name"] == "Fan installation")
        api("DELETE", "/api/v1/cart", None, c_tok)
        cp.goto(BASE + f"/book/{gid}?items={fan_id}:2"); settle(cp)
        cp.wait_for_selector("pg-slot-picker button[aria-pressed]", timeout=15000)
        cp.locator("pg-slot-picker button[aria-pressed]").first.click(); cp.wait_for_timeout(1800)
        cp.goto(BASE + "/services"); settle(cp)
        ok(cp.locator("[data-testid=header-cart-count]").inner_text().strip() == "1", "the header shows the cart once checkout was started")
        cp.goto(BASE + "/"); settle(cp)
        ok("Continue your booking with Ravi" in cp.locator("[data-testid=cart-banner]").inner_text(), "the home page offers to continue the booking")
        cp.locator("[data-testid=cart-banner] a").click()
        cp.wait_for_url(re.compile(r"/cart$")); settle(cp)
        ok("Fan installation ×2" in cp.locator("[data-testid=cart-item]").first.inner_text()
           and "Not chosen" not in cp.locator("[data-testid=cart-time]").inner_text(), "the cart keeps the services and the time")
        overflow(cp, "phone cart")
        shot(cp, "phone-cart")
        cp.reload(); settle(cp)
        ok(cp.locator("[data-testid=cart]").count() == 1, "the cart is still there after a reload (saved in the account)")
        cp.locator("[data-testid=cart-continue]").click()
        cp.wait_for_url(re.compile(r"/book/")); settle(cp)
        cp.wait_for_selector("[data-testid=checkout-from-cart]", timeout=10000)
        cp.wait_for_timeout(800)
        ok(cp.locator(f"[data-testid=qty-value-{fan_id}]").inner_text().strip() == "2"
           and cp.locator("pg-slot-picker button[aria-pressed=true]").count() == 1, "checkout picks up where the customer left off")
        shot(cp, "phone-checkout-from-cart", full=False)
        other_gid = next(g["id"] for g in api("GET", "/api/v1/genies?city=ahmedabad&limit=50")[1] if g["id"] != gid)
        cp.goto(BASE + f"/book/{other_gid}"); settle(cp)
        ok(has(cp, "Replace your cart?"), "another Genie's booking asks before clearing the cart")
        shot(cp, "phone-replace-cart", full=False)
        cp.locator("dialog[open]").get_by_role("button", name="Keep my cart").click()
        cp.wait_for_url(re.compile(r"/cart$")); settle(cp)
        ok(cp.locator("[data-testid=cart]").count() == 1, "keeping the cart goes back to it")
        cp.get_by_role("button", name="Empty cart").click()
        cp.locator("dialog[open]").get_by_role("button", name="Empty cart").click(); cp.wait_for_timeout(1000)
        ok(has(cp, "Your cart is empty"), "the customer can empty the cart")

        print("GUEST picks a service, logs in and the cart follows")
        qctx = browser.new_context(**phone)
        qp = qctx.new_page(); watch(qp, "guest-cart"); PAGES.append(qp)
        qp.goto(BASE + f"/genies/{gid}"); settle(qp)
        qp.get_by_role("button", name=re.compile("Switchboard repair")).first.click(); qp.wait_for_timeout(400)
        qp.get_by_role("button", name="Log in to book").click()
        qp.wait_for_url(re.compile(r"/login")); settle(qp)
        ok(qp.locator("[data-testid=header-cart-count]").count() == 1, "a guest's picks show as a cart before logging in")
        qp.fill("#identifier", "customer@example.com"); qp.locator("input[type=password]").fill(PW)
        qp.get_by_role("button", name=re.compile("^log in$", re.I)).click()
        qp.wait_for_url(re.compile(r"/book/")); settle(qp); qp.wait_for_timeout(2500)
        server_cart = api("GET", "/api/v1/cart", None, c_tok)[1]["cart"]
        ok(server_cart is not None and server_cart["genieId"] == gid, "logging in moves the guest's cart into the account")
        qctx.close()
        api("DELETE", "/api/v1/cart", None, c_tok)

        print("CUSTOMER settings & privacy")
        cp.goto(BASE + "/account/privacy"); settle(cp)
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
        op.wait_for_url(lambda u: "/login" not in u, timeout=15000); settle(op)
        op.goto(BASE + "/account/bookings"); settle(op)
        ok("/account/bookings" in op.url and has(op, "My bookings"), "logged in with a one-time code (bookings open)")
        octx.close()

        print("ADMIN messages, deletions, policies")
        ap.goto(BASE + "/admin/messages"); settle(ap)
        ap.get_by_role("tab", name="Sent").click(); ap.wait_for_timeout(800)
        ok(ap.locator("pg-status").count() > 0, "sent messages are listed")
        shot(ap, "desktop-admin-messages")
        ap.goto(BASE + "/admin/deletions"); settle(ap)
        ok(has(ap, "Account deletions"), "deletion requests page loads")
        ap.goto(BASE + "/admin/legal"); settle(ap)
        ok(has(ap, "Version 1 in force"), "policies list shows the versions in force")
        ap.goto(BASE + "/admin/legal/privacy"); settle(ap)
        ap.wait_for_selector("textarea[name=body]")
        ap.locator("textarea[name=body]").press("Control+End")
        ap.locator("textarea[name=body]").type("\n\n## Photos in complaints\n\nPhotos you add to a report are kept for **2 years**, then deleted.")
        ok(ap.locator("pg-markdown h2", has_text="Photos in complaints").count() == 1, "live Markdown preview updates")
        ap.fill("#l-sum", "Added how long complaint photos are kept")
        ap.locator("input[name=re]").check()
        shot(ap, "desktop-admin-legal-editor")
        ap.get_by_role("button", name="Publish…").click()
        ap.locator("dialog[open]").get_by_role("button", name="Publish", exact=True).click()
        ap.wait_for_url(re.compile(r"/admin/legal$"), timeout=15000); settle(ap)
        ok(has(ap, "Version 2 in force"), "Privacy Policy v2 published")

        print("CUSTOMER must accept the new policy")
        cp.goto(BASE + "/account/bookings"); settle(cp)
        cp.reload(); settle(cp)
        ok(has(cp, "We've updated our policies"), "consent dialog appears after a policy change")
        shot(cp, "phone-consent-dialog", full=False)
        cp.locator("dialog[open] input[name=agree]").check()
        cp.get_by_role("button", name="Accept and continue").click(); cp.wait_for_timeout(1200)
        ok(not has(cp, "We've updated our policies", timeout=1500), "consent dialog closes after accepting")
        cp.goto(BASE + "/legal/privacy"); settle(cp)
        ok(has(cp, "Photos in complaints") and has(cp, "Earlier versions"), "public policy page shows v2 and older versions")
        overflow(cp, "phone legal page")
        shot(cp, "phone-legal-privacy")
        ap.goto(BASE + "/admin/legal/refunds"); settle(ap)
        ok(ap.locator("input[name=re]").count() == 0 and has(ap, "part of the Terms"), "refund policy editor explains it is accepted via the Terms")

        # tablet sweep for overflow on key pages
        tctx = browser.new_context(viewport={"width": 768, "height": 1024}, **IN)
        tp = tctx.new_page(); watch(tp, "tablet-admin")
        login(tp, "admin@progenie.in")
        for path in ["/admin", "/admin/genies", "/admin/bookings", "/admin/users", "/admin/catalog", "/admin/finance", "/admin/coupons", "/admin/demand", "/admin/review-photos", "/admin/help", "/admin/inbox",
                     "/admin/tickets", f"/admin/tickets/{ticket_id}", f"/admin/bookings/{booking_id}", "/admin/messages", "/admin/legal",
                     "/admin/legal/privacy", "/admin/deletions", "/admin/settings"]:
            tp.goto(BASE + path); settle(tp); tp.wait_for_timeout(300)
            overflow(tp, f"tablet {path}")
        shot(tp, "tablet-admin-overview")

        # narrow phone (360) sweep for customer + genie pages
        sctx = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, has_touch=True, **IN)
        sp = sctx.new_page(); watch(sp, "360-customer")
        login(sp, "customer@example.com")
        for path in ["/", "/services", "/services/electrician", "/genies/1af855e6-31a8-5caa-bffa-6f864b9644ff", "/book/1af855e6-31a8-5caa-bffa-6f864b9644ff",
                     "/account/bookings", booking_url.replace(BASE, ""), "/account/addresses", "/account/favourites", "/account/profile", "/notifications",
                     "/account/tickets", ticket_url.replace(BASE, ""), "/account/privacy", "/help", "/legal/terms", "/legal/privacy"]:
            sp.goto(BASE + path); settle(sp); sp.wait_for_timeout(400)
            overflow(sp, f"360 {path}")
        shot(sp, "360-booking-detail")
        s2 = sctx.new_page(); watch(s2, "360-genie")
        sctx.clear_cookies()
        login(s2, "genie@example.com")
        for path in ["/genie", "/genie/jobs", f"/genie/bookings/{booking_id}", "/genie/onboarding", "/genie/availability", "/genie/wallet", "/genie/reviews",
                     "/genie/tickets", f"/genie/tickets/{ticket_id}", "/genie/privacy", "/help?category=GENIES"]:
            s2.goto(BASE + path); settle(s2); s2.wait_for_timeout(400)
            overflow(s2, f"360 {path}")
        shot(s2, "360-genie-dashboard")
        # guest pages at 360
        gctx2 = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, has_touch=True, **IN)
        g2 = gctx2.new_page(); watch(g2, "360-guest")
        g2.goto(BASE + "/track/not-a-real-token-000000000000000000000000000000"); settle(g2)
        ok(has(g2, "This tracking link has expired"), "an old or wrong tracking link says it has expired (no login needed)")
        # Phase 3.5B: the help centre needs no login
        g2.goto(BASE + "/help"); settle(g2)
        g2.wait_for_selector("[data-testid=help-article]", timeout=10000)
        g2.fill("#help-q", "start code")
        g2.wait_for_timeout(300)
        found = g2.locator("[data-testid=help-article]")
        ok(found.count() >= 1 and all("start" in t and "code" in t for t in (found.nth(i).inner_text().lower() for i in range(found.count()))),
           f"help centre search finds answers about the start code ({found.count()})")
        found.first.get_by_role("button", name="Yes").click(); g2.wait_for_timeout(500)
        ok(has(g2, "Thanks for telling us"), "a guest can say an answer helped")
        shot(g2, "360-help-centre")
        g2.goto(BASE + "/help?category=GENIES"); settle(g2)
        ok([h.strip() for h in g2.locator("main h2").all_inner_texts()] == ["For Genies"], "the Genie console's link opens For Genies only")
        for path in ["/login", "/forgot-password", "/register", "/register?role=GENIE", "/legal/genie-agreement", "/help"]:
            g2.goto(BASE + path); settle(g2); g2.wait_for_timeout(300)
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
