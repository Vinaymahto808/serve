#!/usr/bin/env python3
"""Set up Google Drive service account for NetGuardian captues."""

import json
from pathlib import Path

SETUP_FILE = Path(__file__).parent / "service_account.json"

print("=" * 60)
print("NetGuardian Google Drive Setup")
print("=" * 60)
print()
print("This script helps you set up Google Drive API access.")
print()
print("Steps to get your service_account.json:")
print()
print("1. Go to https://console.cloud.google.com/")
print("2. Create a new project (or select existing)")
print("3. Enable 'Google Drive API' for the project")
print("4. Go to 'IAM & Admin' -> 'Service Accounts'")
print("5. Create a new service account (name: netguardian)")
print("6. Click the service account -> 'Keys' -> 'Add Key' -> JSON")
print("7. Download the JSON file")
print("8. Share your Drive folder with the service account email")
print("   (The email is in the JSON file under 'client_email')")
print()
print(f"9. Copy the downloaded JSON to: {SETUP_FILE}")
print()
print("10. The folder ID is from the URL:")
print("    https://drive.google.com/drive/folders/FOLDER_ID")
print()

if SETUP_FILE.exists():
    print(f"✅ service_account.json already exists at {SETUP_FILE}")
    with open(SETUP_FILE) as f:
        data = json.load(f)
    print(f"   Service account: {data.get('client_email', 'unknown')}")
else:
    print(f"❌ service_account.json not found at {SETUP_FILE}")
    print()
    print("After placing the JSON file, re-run this script to verify.")
    print()

print("=" * 60)
