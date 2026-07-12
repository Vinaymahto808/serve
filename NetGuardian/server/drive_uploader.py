"""
Google Drive uploader for NetGuardian captures.

Setup:
1. Go to https://console.cloud.google.com/ -> Create Project -> Enable Google Drive API
2. Create Service Account -> Download JSON key
3. Save key as 'service_account.json' in this directory
4. Share the target Drive folder with the service account email
5. The folder ID is from the URL: https://drive.google.com/drive/folders/FOLDER_ID
"""

import json
import os
from pathlib import Path

try:
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload
    GOOGLE_DRIVE_AVAILABLE = True
except ImportError:
    GOOGLE_DRIVE_AVAILABLE = False

SERVICE_ACCOUNT_FILE = Path(__file__).parent / "service_account.json"
TARGET_FOLDER_ID = "1tpAkKhiyWQu009jj5hF_UNHIaFAy1afs"

_drive_service = None

def get_drive_service():
    global _drive_service
    if _drive_service:
        return _drive_service
    if not GOOGLE_DRIVE_AVAILABLE or not SERVICE_ACCOUNT_FILE.exists():
        return None
    creds = service_account.Credentials.from_service_account_file(
        str(SERVICE_ACCOUNT_FILE),
        scopes=["https://www.googleapis.com/auth/drive.file"]
    )
    _drive_service = build("drive", "v3", credentials=creds)
    return _drive_service

def is_configured():
    return GOOGLE_DRIVE_AVAILABLE and SERVICE_ACCOUNT_FILE.exists()

def upload_file(file_path: str, mime_type: str = None) -> dict | None:
    """Upload a file to the target Drive folder. Returns file info dict or None."""
    service = get_drive_service()
    if not service:
        return None

    if mime_type is None:
        ext = Path(file_path).suffix.lower()
        mime_map = {
            ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".png": "image/png", ".gif": "image/gif",
            ".mp4": "video/mp4", ".mp3": "audio/mp3",
            ".aac": "audio/aac", ".m4a": "audio/mp4",
        }
        mime_type = mime_map.get(ext, "application/octet-stream")

    file_metadata = {
        "name": Path(file_path).name,
        "parents": [TARGET_FOLDER_ID]
    }
    media = MediaFileUpload(file_path, mimetype=mime_type, resumable=True)

    try:
        drive_file = service.files().create(
            body=file_metadata,
            media_body=media,
            fields="id, name, mimeType, webViewLink, webContentLink"
        ).execute()
        return {
            "id": drive_file.get("id"),
            "name": drive_file.get("name"),
            "mimeType": drive_file.get("mimeType"),
            "webViewLink": drive_file.get("webViewLink"),
            "webContentLink": drive_file.get("webContentLink"),
        }
    except Exception as e:
        print(f"Drive upload error: {e}")
        return None

def get_embed_url(file_id: str, mime_type: str) -> str:
    """Get an embed/view URL for a Drive file."""
    if mime_type and mime_type.startswith("image/"):
        return f"https://drive.google.com/uc?id={file_id}"
    elif mime_type and mime_type.startswith("video/"):
        return f"https://drive.google.com/file/d/{file_id}/preview"
    elif mime_type and mime_type.startswith("audio/"):
        return f"https://drive.google.com/uc?export=download&id={file_id}"
    return f"https://drive.google.com/file/d/{file_id}/view"
