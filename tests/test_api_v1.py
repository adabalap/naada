"""
API v1 tests. JioSaavn is mocked, so these run offline:

    NAADA_TOKEN=t python -m pytest -q tests/        (or)  python tests/test_api_v1.py
"""
import os
import sys
import tempfile
import unittest

os.environ["NAADA_TOKEN"] = "test-token"
os.environ["NAADA_DB"] = os.path.join(tempfile.mkdtemp(), "library.db")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import app as naada  # noqa: E402

SONG = {"id": "abc123", "title": "Samajavaragamana", "type": "song",
        "more_info": {"album": "Ala Vaikunthapurramuloo", "duration": "228",
                      "media_url": "https://aac.saavncdn.com/x/abc_96.mp4",
                      "artistMap": {"primary_artists": [{"name": "Sid Sriram"}]}},
        "image": "https://c.saavncdn.com/a-150x150.jpg"}


def fake_saavn(params, timeout=12):
    call = params.get("__call")
    if call == "song.getDetails":
        return {SONG["id"]: SONG} if params["pids"] == SONG["id"] else {}
    if call == "search.getResults":
        return {"results": [SONG]}
    return {}


naada.saavn = fake_saavn
H = {"X-Naada-Token": "test-token"}


class V1(unittest.TestCase):
    def setUp(self):
        self.c = naada.app.test_client()

    def test_auth_required(self):
        self.assertEqual(self.c.get("/api/v1/info").status_code, 401)
        self.assertEqual(self.c.get("/api/v1/info", headers={"X-Naada-Token": "nope"}).status_code, 401)
        self.assertEqual(self.c.get("/api/v1/info", headers={"Authorization": "Bearer test-token"}).status_code, 200)

    def test_pwa_cookie_still_works(self):
        r = self.c.get("/?k=test-token")
        self.assertEqual(r.status_code, 302)
        self.assertEqual(self.c.get("/api/search?q=x").status_code, 200)

    def test_info(self):
        j = self.c.get("/api/v1/info", headers=H).get_json()
        self.assertEqual(j["api_version"], 1)
        self.assertIn("library_sync", j["features"])

    def test_aliases(self):
        j = self.c.get("/api/v1/search?q=sid", headers=H).get_json()
        self.assertEqual(j["songs"][0]["id"], "abc123")
        self.assertTrue(j["songs"][0]["url"].endswith("_320.mp4"))
        old = self.c.get("/api/search?q=sid", headers=H).get_json()
        self.assertEqual(old, j)

    def test_stream(self):
        j = self.c.get("/api/v1/stream/abc123", headers=H).get_json()
        self.assertTrue(j["url"].startswith("https://"))
        self.assertNotIn("url", j["song"])
        self.assertEqual(self.c.get("/api/v1/stream/missing", headers=H).status_code, 404)
        self.assertEqual(self.c.get("/api/song/abc123", headers=H).get_json()["id"], "abc123")

    def test_library_flow(self):
        song = {"id": "abc123", "title": "Samajavaragamana", "url": "https://expiring"}
        r = self.c.put("/api/v1/library/likes/abc123", json=song, headers=H)
        self.assertEqual(r.status_code, 200)
        rev = r.get_json()["rev"]
        lib = self.c.get("/api/v1/library", headers=H).get_json()
        self.assertEqual(lib["likes"][0]["id"], "abc123")
        self.assertNotIn("url", lib["likes"][0])
        self.assertTrue(self.c.get(f"/api/v1/library?since_rev={rev}", headers=H).get_json()["unchanged"])

        r = self.c.post("/api/v1/library/playlists", json={"name": "Drive", "songs": [song]}, headers=H)
        self.assertEqual(r.status_code, 201)
        pid = r.get_json()["id"]
        self.c.put(f"/api/v1/library/playlists/{pid}", json={"name": "Long drive", "songs": [song, song]}, headers=H)
        pl = [p for p in self.c.get("/api/v1/library", headers=H).get_json()["playlists"] if p["id"] == pid][0]
        self.assertEqual(pl["name"], "Long drive")
        self.assertEqual(len(pl["songs"]), 2)

        items = [{"song": song, "played_at": 1700000000.5}]
        self.assertEqual(self.c.post("/api/v1/library/history", json={"items": items}, headers=H).get_json()["added"], 1)
        self.assertEqual(self.c.post("/api/v1/library/history", json={"items": items}, headers=H).get_json()["added"], 0)

        self.c.delete(f"/api/v1/library/playlists/{pid}", headers=H)
        self.c.delete("/api/v1/library/likes/abc123", headers=H)
        lib = self.c.get("/api/v1/library", headers=H).get_json()
        self.assertFalse(any(p["id"] == pid for p in lib["playlists"]))
        self.assertFalse(any(s["id"] == "abc123" for s in lib["likes"]))

    def test_import(self):
        body = {"likes": [{"id": "s1", "title": "A"}, {"bad": 1}],
                "playlists": [{"name": "Old", "songs": [{"id": "s2"}]}],
                "history": [{"song": {"id": "s3"}, "played_at": 5}]}
        j = self.c.post("/api/v1/library/import", json=body, headers=H).get_json()
        self.assertEqual((j["likes"], j["playlists"], j["history"]), (1, 1, 1))

    def test_validation(self):
        self.assertEqual(self.c.put("/api/v1/library/likes/x", json={"id": "y"}, headers=H).status_code, 400)
        self.assertEqual(self.c.post("/api/v1/library/playlists", json={}, headers=H).status_code, 400)


if __name__ == "__main__":
    unittest.main(verbosity=2)
