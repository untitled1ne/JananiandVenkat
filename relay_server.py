"""
Emote Buddy - Relay Server
---------------------------
A tiny WebSocket relay. Clients join a "room" (just a shared secret code you
both agree on) and any emote one sends gets forwarded to the other person(s)
in the same room. That's it - no accounts, no database.

DEPLOY THIS ONCE (free options):
- Render.com (Web Service, free tier)
- Railway.app (free trial credits)
- Fly.io (free allowance)

All three let you deploy a small Python app for free. Steps are basically:
1. Push this file (plus requirements.txt) to a GitHub repo
2. Connect that repo on Render/Railway/Fly
3. Set the start command to: python relay_server.py
4. They give you a public URL like wss://your-app.onrender.com

Put that URL into emote_buddy.py's SERVER_URL once deployed.
"""

import asyncio
import json
import websockets

# room_code -> set of connected websocket clients
rooms = {}


async def handler(websocket):
    room_code = None
    try:
        async for raw_message in websocket:
            data = json.loads(raw_message)

            if data.get("type") == "join":
                room_code = data["room"]
                rooms.setdefault(room_code, set()).add(websocket)
                await websocket.send(json.dumps({"type": "joined", "room": room_code}))
                continue

            if data.get("type") == "emote" and room_code:
                # forward to everyone else in the same room
                peers = rooms.get(room_code, set())
                dead = set()
                for peer in peers:
                    if peer is websocket:
                        continue
                    try:
                        await peer.send(raw_message)
                    except websockets.ConnectionClosed:
                        dead.add(peer)
                peers -= dead

    except websockets.ConnectionClosed:
        pass
    finally:
        if room_code and room_code in rooms:
            rooms[room_code].discard(websocket)
            if not rooms[room_code]:
                del rooms[room_code]


async def main():
    import os
    port = int(os.environ.get("PORT", 8765))
    async with websockets.serve(handler, "0.0.0.0", port):
        print(f"Relay server running on port {port}")
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
