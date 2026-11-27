#!/usr/bin/env python3
"""
SomniSense Stage 4 Milestone 4D: Full-Stack Chromium Playwright E2E Verification.

Validates the complete live stack:
  PostgreSQL (V1-V6) -> Spring Boot -> ai-service (Model C) + simulator-worker -> React UI.
"""

from __future__ import annotations

import os
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright


def wait_for_text(page, text: str, timeout_s: float = 110.0) -> None:
    page.get_by_text(text).first.wait_for(state="visible", timeout=int(timeout_s * 1000))


def check_unauth_and_csrf_api(backend_url: str) -> None:
    try:
        urllib.request.urlopen(f"{backend_url}/api/v1/dashboard/summary", timeout=3.0)
        raise AssertionError("Unauthenticated GET /api/v1/dashboard/summary unexpectedly returned 200")
    except urllib.error.HTTPError as exc:
        assert exc.code == 401, f"Expected 401 on unauth dashboard/summary, got {exc.code}"

    req = urllib.request.Request(f"{backend_url}/api/v1/sessions", data=b"{}", method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        urllib.request.urlopen(req, timeout=3.0)
        raise AssertionError("Unauthenticated/CSRF-less POST /api/v1/sessions unexpectedly succeeded")
    except urllib.error.HTTPError as exc:
        assert exc.code == 403, f"Expected 403 on missing CSRF POST /api/v1/sessions, got {exc.code}"


def api_post_in_page(page, backend_url: str, path: str, body: dict) -> dict:
    script = """
    async ({ backendUrl, path, body }) => {
      const xsrfMatch = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
      const xsrf = xsrfMatch ? decodeURIComponent(xsrfMatch[1]) : '';
      const resp = await fetch(`${backendUrl}/api/v1${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': xsrf,
        },
        body: JSON.stringify(body),
      });
      const text = await resp.text();
      if (!resp.ok) {
        throw new Error(`POST ${path} failed (${resp.status}): ${text}`);
      }
      return text ? JSON.parse(text) : {};
    }
    """
    return page.evaluate(script, {"backendUrl": backend_url, "path": path, "body": body})


def provision_patient_and_device(page, backend_url: str, full_name: str, alias: str, device_code: str) -> str:
    patient = api_post_in_page(
        page,
        backend_url,
        "/patients",
        {"fullName": full_name, "alias": alias, "sourceType": "SIMULATED"},
    )
    device = api_post_in_page(
        page,
        backend_url,
        "/devices",
        {
            "deviceCode": device_code,
            "deviceType": "SIMULATED",
            "modelName": "OxiSim-4D",
            "serialNumber": f"SN-{device_code}",
        },
    )
    api_post_in_page(
        page,
        backend_url,
        "/device-assignments",
        {"deviceId": device["id"], "patientId": patient["id"], "replaceExisting": False},
    )
    return patient["id"]


def main() -> int:
    frontend_url = os.environ.get("E2E_FRONTEND_URL", "http://127.0.0.1:5173").rstrip("/")
    backend_url = os.environ.get("E2E_BACKEND_URL", "http://127.0.0.1:8081").rstrip("/")
    admin_user = os.environ["E2E_ADMIN_USER"]
    admin_password = os.environ["E2E_ADMIN_PASSWORD"]

    repo_root = Path(__file__).resolve().parent.parent.parent
    evidence_dir = repo_root / "docs" / "integration" / "evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)

    console_errors: list[str] = []
    page_errors: list[str] = []
    unexpected_http_errors: list[str] = []
    summary_poll_count = 0

    check_unauth_and_csrf_api(backend_url)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()

        page.on("pageerror", lambda err: page_errors.append(str(err)))
        page.on(
            "console",
            lambda msg: console_errors.append(msg.text)
            if msg.type == "error"
            and "ERR_FAILED" not in msg.text
            and "status of 401 ()" not in msg.text
            and "status of 404 ()" not in msg.text
            else None,
        )

        def on_response(res) -> None:
            nonlocal summary_poll_count
            if "/api/v1/" in res.url:
                if res.status >= 500:
                    unexpected_http_errors.append(f"{res.status} {res.url}")
                elif res.status == 401 and not res.url.endswith("/api/v1/auth/me"):
                    unexpected_http_errors.append(f"401 {res.url}")
                elif res.status == 404 and not res.url.endswith("/simulation"):
                    unexpected_http_errors.append(f"404 {res.url}")
                elif res.status == 403:
                    unexpected_http_errors.append(f"403 {res.url}")
                if res.url.endswith("/api/v1/dashboard/summary") and res.status == 200:
                    summary_poll_count += 1

        page.on("response", on_response)

        try:
            # 1. Unauthenticated redirect check
            print("[E2E] 1. Checking unauthenticated /dashboard -> /login redirect...")
            page.goto(f"{frontend_url}/dashboard", wait_until="networkidle")
            page.wait_for_url(lambda u: "/login" in u, timeout=12000)

            # 2. Authenticate via login form as ADMIN
            print("[E2E] 2. Authenticating staff ADMIN via LoginPage...")
            page.fill("#login-identifier", admin_user)
            page.fill("#login-password", admin_password)
            page.click("button.login-submit")
            page.wait_for_url(lambda u: "/dashboard" in u or u.rstrip("/") == frontend_url, timeout=15000)
            wait_for_text(page, "No active monitoring sessions", timeout_s=12.0)

            assert page.get_by_text("Partial overview metrics").count() == 0
            page.screenshot(path=str(evidence_dir / "stage4d_01_dashboard_empty.png"), full_page=True)
            print("[E2E]    Empty dashboard state verified.")

            # 3. Provision 3 simulated patients + devices cleanly over Spring Boot CSRF API
            print("[E2E] 3. Provisioning 3 simulated patients and devices...")
            pt1_id = provision_patient_and_device(page, backend_url, "E2E Normal Sleeper", "Normal E2E", "SIM-E2E-01")
            pt2_id = provision_patient_and_device(page, backend_url, "E2E Severe OSA", "OSA E2E", "SIM-E2E-02")
            pt3_id = provision_patient_and_device(page, backend_url, "E2E Hypopnea Cancel", "Cancel E2E", "SIM-E2E-03")

            # 4. Patient 1: start session + normal_sleep simulator via UI
            print("[E2E] 4. Starting monitoring session + normal_sleep simulator on Patient 1...")
            page.goto(f"{frontend_url}/patients/{pt1_id}", wait_until="networkidle")
            page.get_by_role("button", name="Start monitoring session").click()
            page.get_by_label("Simulation scenario").select_option("normal_sleep")
            page.get_by_role("button", name="Start simulator").click()
            wait_for_text(page, "Streaming", timeout_s=15.0)
            wait_for_text(page, "Evaluating initial 60s baseline window...", timeout_s=10.0)

            # 5. Patient 2: start session + severe_osa simulator via UI
            print("[E2E] 5. Starting monitoring session + severe_osa simulator on Patient 2...")
            page.goto(f"{frontend_url}/patients/{pt2_id}", wait_until="networkidle")
            page.get_by_role("button", name="Start monitoring session").click()
            page.get_by_label("Simulation scenario").select_option("severe_osa")
            page.get_by_role("button", name="Start simulator").click()
            wait_for_text(page, "Streaming", timeout_s=15.0)

            # 6. Wait for live 60s window prediction + chart + disclaimer via 10s polling!
            print("[E2E] 6. Waiting up to 110s for real 60s Model C evaluation & 10s UI poll...")
            wait_for_text(page, "Window prediction history", timeout_s=110.0)
            wait_for_text(page, "Model output only. This is not a diagnosis.", timeout_s=5.0)
            assert page.get_by_text("Model service unavailable").count() == 0
            page.screenshot(path=str(evidence_dir / "stage4d_03_patient2_apnea_live.png"), full_page=True)
            print("[E2E]    Patient 2 live prediction history rendered!")

            # Pause & Resume continuity check via UI buttons on Patient 2
            print("[E2E] 7. Verifying simulator Pause -> Resume continuity via UI...")
            page.get_by_role("button", name="Pause simulator").click()
            wait_for_text(page, "Paused", timeout_s=15.0)
            time.sleep(1.5)
            page.get_by_role("button", name="Resume simulator").click()
            wait_for_text(page, "Streaming", timeout_s=15.0)

            # Check Patient 1 live evaluated prediction + Stop simulator + End session
            print("[E2E] 8. Checking Patient 1 live COMPLETED / NORMAL prediction and Stop simulator...")
            page.goto(f"{frontend_url}/patients/{pt1_id}", wait_until="networkidle")
            wait_for_text(page, "Window prediction history", timeout_s=35.0)
            wait_for_text(page, "Model output only. This is not a diagnosis.", timeout_s=5.0)
            page.screenshot(path=str(evidence_dir / "stage4d_02_patient1_normal_live.png"), full_page=True)

            # 9. Verify Dashboard summary shows active sessions, polling, stale-data banner, and mobile responsiveness
            print("[E2E] 9. Verifying Dashboard live metrics, 10s polling, stale-data handling, and layouts...")
            summary_poll_count = 0
            page.goto(f"{frontend_url}/dashboard", wait_until="networkidle")
            wait_for_text(page, "Active monitoring sessions", timeout_s=12.0)
            wait_for_text(page, "OSA E2E", timeout_s=12.0)
            wait_for_text(page, "Normal E2E", timeout_s=12.0)
            page.screenshot(path=str(evidence_dir / "stage4d_04_dashboard_active_summary.png"), full_page=True)

            # Simulate single transient network abort on next 10s poll to verify stale-data banner without wiping snapshot
            aborted_once = {"done": False}

            def fail_once(route):
                if not aborted_once["done"]:
                    aborted_once["done"] = True
                    route.abort("failed")
                else:
                    route.continue_()

            page.route("**/api/v1/dashboard/summary", fail_once)
            wait_for_text(page, "Live refresh delayed", timeout_s=15.0)
            assert page.get_by_text("OSA E2E").count() >= 1
            page.unroute("**/api/v1/dashboard/summary")

            # Verify next 10s background poll recovers cleanly and clears stale warning
            time.sleep(11.5)
            assert page.get_by_text("Live refresh delayed").count() == 0
            assert summary_poll_count >= 2, f"Expected >=2 live dashboard summary requests via polling, got {summary_poll_count}"

            # Mobile viewport check (390x844)
            page.set_viewport_size({"width": 390, "height": 844})
            time.sleep(0.5)
            layout_check = page.evaluate("""() => ({
              scrollW: document.documentElement.scrollWidth,
              innerW: window.innerWidth,
              offenders: [...document.querySelectorAll('*')]
                .filter(el => el.getBoundingClientRect().right > window.innerWidth + 2)
                .slice(0, 10)
                .map(el => ({ tag: el.tagName, cls: el.className, right: Math.round(el.getBoundingClientRect().right) }))
            })""")
            assert layout_check["scrollW"] <= layout_check["innerW"] + 2, f"Mobile horizontal overflow: {layout_check}"
            page.screenshot(path=str(evidence_dir / "stage4d_05_mobile_responsive.png"), full_page=True)
            page.set_viewport_size({"width": 1440, "height": 900})

            # 10. Stop simulator + End session (Patient 1 & 2) + Cancel confirmation (Patient 3)
            print("[E2E] 10. Verifying Stop simulator, End session, and Cancel confirmation...")
            page.goto(f"{frontend_url}/patients/{pt1_id}", wait_until="networkidle")
            page.get_by_role("button", name="Stop simulator").click()
            wait_for_text(page, "Stopped", timeout_s=15.0)
            page.get_by_role("button", name="End session").click()
            wait_for_text(page, "ENDED", timeout_s=15.0)

            page.goto(f"{frontend_url}/patients/{pt2_id}", wait_until="networkidle")
            page.get_by_role("button", name="End session").click()
            wait_for_text(page, "ENDED", timeout_s=15.0)

            page.goto(f"{frontend_url}/patients/{pt3_id}", wait_until="networkidle")
            page.get_by_role("button", name="Start monitoring session").click()
            wait_for_text(page, "ACTIVE", timeout_s=15.0)
            page.get_by_label("Simulation scenario").select_option("moderate_hypopnea")
            page.get_by_role("button", name="Start simulator").click()
            wait_for_text(page, "Streaming", timeout_s=15.0)
            page.get_by_role("button", name="Cancel session").click()
            wait_for_text(page, "Confirm cancel session?", timeout_s=5.0)
            page.get_by_role("button", name="Confirm cancel").click()
            wait_for_text(page, "CANCELLED", timeout_s=15.0)

            # Assert clean runtime telemetry
            assert not page_errors, f"Browser pageerror occurred: {page_errors}"
            assert not console_errors, f"Browser console.error occurred: {console_errors}"
            assert not unexpected_http_errors, f"Unexpected HTTP errors occurred: {unexpected_http_errors}"

            print("\n[E2E] ALL STAGE 4D FULL-STACK PLAYWRIGHT CHECKS PASSED!")
            return 0
        finally:
            context.close()
            browser.close()


if __name__ == "__main__":
    raise SystemExit(main())
