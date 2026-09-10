(function () {
  // ---------------- starfield background ----------------
  const canvas = document.getElementById('starfield');
  const ctx = canvas.getContext('2d');
  let stars = [];
  const STAR_COUNT = 160;

  function resizeCanvas() {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
  }

  function randomStar(initial) {
    const w = canvas.width || 1;
    return {
      x: Math.random() * w - w / 2,
      y: Math.random() * (canvas.height || 1) - (canvas.height || 1) / 2,
      z: initial ? Math.random() * w : w
    };
  }

  function initStars() {
    stars = [];
    for (let i = 0; i < STAR_COUNT; i++) stars.push(randomStar(true));
  }

  window.addEventListener('resize', resizeCanvas);
  resizeCanvas();
  initStars();

  function drawStars() {
    const w = canvas.width, h = canvas.height;
    const cx = w / 2, cy = h / 2;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#ffffff';
    for (const s of stars) {
      s.z -= 0.6;
      if (s.z <= 1) Object.assign(s, randomStar(false));
      const sx = cx + (s.x / s.z) * cx;
      const sy = cy + (s.y / s.z) * cy;
      if (sx < 0 || sx > w || sy < 0 || sy > h) continue;
      const depth = 1 - s.z / w;
      const r = 0.5 + depth * 2;
      ctx.globalAlpha = 0.3 + depth * 0.7;
      ctx.beginPath();
      ctx.arc(sx, sy, r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;
    requestAnimationFrame(drawStars);
  }
  requestAnimationFrame(drawStars);

  // ---------------- signaling / sharing ----------------
  const joinCard = document.getElementById('joinCard');
  const shareCard = document.getElementById('shareCard');
  const codeInput = document.getElementById('codeInput');
  const joinError = document.getElementById('joinError');
  const shareError = document.getElementById('shareError');
  const btnJoin = document.getElementById('btnJoin');
  const btnShare = document.getElementById('btnShare');
  const btnStop = document.getElementById('btnStop');
  const btnStop2 = document.getElementById('btnStop2');
  const connectingOverlay = document.getElementById('connectingOverlay');
  const successOverlay = document.getElementById('successOverlay');
  const liveOverlay = document.getElementById('liveOverlay');
  const checkCircle = document.getElementById('checkCircle');

  let ws = null;
  let pc = null;
  let localStream = null;
  let joinTimeoutId = null;

  function showOnly(el) {
    [joinCard, shareCard, connectingOverlay, successOverlay, liveOverlay].forEach((e) => {
      if (!e) return;
      e.hidden = e !== el;
    });
  }

  function setJoinBusy(busy) {
    btnJoin.disabled = busy;
    btnJoin.textContent = busy ? '연결 중…' : '연결';
  }

  function connectSocket(onOpen, onError) {
    if (!window.WebSocket) {
      onError('이 브라우저는 WebSocket을 지원하지 않습니다.');
      return;
    }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    try {
      ws = new WebSocket(`${proto}://${location.host}/ws`);
    } catch (e) {
      onError('서버에 연결할 수 없습니다: ' + e.message);
      return;
    }
    ws.onopen = () => onOpen && onOpen();
    ws.onmessage = (evt) => handleServerMessage(JSON.parse(evt.data));
    ws.onerror = () => onError && onError('서버에 연결할 수 없습니다. 같은 Wi-Fi인지, 주소가 맞는지 확인하세요.');
    ws.onclose = () => { ws = null; };
  }

  function handleServerMessage(msg) {
    switch (msg.type) {
      case 'joined':
        clearTimeout(joinTimeoutId);
        setJoinBusy(false);
        joinError.textContent = '';
        showOnly(shareCard);
        break;
      case 'error':
        clearTimeout(joinTimeoutId);
        setJoinBusy(false);
        joinError.textContent = msg.message || '연결에 실패했습니다.';
        break;
      case 'answer':
        pc && pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp }).catch((e) => {
          shareError.textContent = '연결 오류: ' + e.message;
        });
        break;
      case 'ice':
        if (pc && msg.candidate) {
          pc.addIceCandidate({ candidate: msg.candidate, sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex }).catch(() => {});
        }
        break;
      case 'bye':
        cleanupCall();
        showOnly(shareCard);
        break;
    }
  }

  btnJoin.addEventListener('click', () => {
    const code = codeInput.value.trim();
    if (code.length !== 6) {
      joinError.textContent = '6자리 코드를 입력하세요.';
      return;
    }
    joinError.textContent = '';
    setJoinBusy(true);

    clearTimeout(joinTimeoutId);
    joinTimeoutId = setTimeout(() => {
      setJoinBusy(false);
      joinError.textContent = '연결 시간이 초과되었습니다. 같은 Wi-Fi인지 확인하고 다시 시도하세요.';
      if (ws) { try { ws.close(); } catch (e) {} }
    }, 7000);

    connectSocket(
      () => ws.send(JSON.stringify({ type: 'join', code })),
      (message) => {
        clearTimeout(joinTimeoutId);
        setJoinBusy(false);
        joinError.textContent = message;
      }
    );
  });

  codeInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') btnJoin.click();
  });

  async function startShare() {
    shareError.textContent = '';
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      shareError.textContent = '이 브라우저는 화면 공유를 지원하지 않습니다. 최신 Chrome을 사용하세요.';
      return;
    }
    showOnly(connectingOverlay);
    try {
      // 1080p is the sweet spot for this: visually lossless for screen content, but
      // realistically encodable in real time on ordinary hardware. Asking for
      // 1440p/4K "ideal" invites the encoder to fall behind under load, which is what
      // actually produces visible lag/latency - not the network (LAN has headroom).
      // 30fps target (vs 60) keeps frame timing consistent instead of oscillating,
      // which is what stutter/choppiness actually is - an unstable frame interval,
      // not a low one.
      const videoConstraints = { width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 30, max: 30 } };
      let stream;
      try {
        stream = await navigator.mediaDevices.getDisplayMedia({ video: videoConstraints, audio: true });
      } catch (e) {
        stream = await navigator.mediaDevices.getDisplayMedia({ video: videoConstraints, audio: false });
      }
      localStream = stream;

      pc = new RTCPeerConnection({ iceServers: [] });
      stream.getTracks().forEach((track) => {
        track.addEventListener('ended', () => stopShare());
        pc.addTrack(track, stream);
      });

      // Prefer H.264 first: most systems have a hardware H.264 encoder, which keeps
      // encode time low and steady (avoiding the backlog that causes growing lag)
      // in a way software VP8/VP9 usually can't match on typical Wi-Fi laptops.
      const videoTransceiver = pc.getTransceivers().find((t) => t.sender && t.sender.track === stream.getVideoTracks()[0]);
      if (videoTransceiver && typeof videoTransceiver.setCodecPreferences === 'function' && window.RTCRtpSender && RTCRtpSender.getCapabilities) {
        try {
          const caps = RTCRtpSender.getCapabilities('video');
          if (caps && caps.codecs) {
            const h264 = caps.codecs.filter((c) => /H264/i.test(c.mimeType));
            const rest = caps.codecs.filter((c) => !/H264/i.test(c.mimeType));
            if (h264.length) videoTransceiver.setCodecPreferences([...h264, ...rest]);
          }
        } catch (e) { /* best effort - fall back to default codec negotiation */ }
      }

      const videoSender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
      if (videoSender) {
        const params = videoSender.getParameters();
        if (!params.encodings) params.encodings = [{}];
        params.encodings[0].maxBitrate = 12000000;
        // 'balanced' (not 'maintain-resolution'): forcing fixed resolution under any
        // transient CPU/network pressure makes the encoder cope by dropping frames in
        // hard steps instead of smoothly, which is exactly what stutter looks like.
        // 'balanced' lets WebRTC's own adaptation smooth that out; resolution only
        // drops if genuinely needed, which real LAN/hardware headroom rarely requires.
        params.degradationPreference = 'balanced';
        try { await videoSender.setParameters(params); } catch (e) { /* best effort */ }
      }

      pc.onicecandidate = (evt) => {
        if (evt.candidate && ws) {
          ws.send(JSON.stringify({
            type: 'ice',
            candidate: evt.candidate.candidate,
            sdpMid: evt.candidate.sdpMid,
            sdpMLineIndex: evt.candidate.sdpMLineIndex
          }));
        }
      };

      pc.onconnectionstatechange = () => {
        if (!pc) return;
        if (pc.connectionState === 'connected') {
          onConnected();
        } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') {
          stopShare();
        }
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        throw new Error('신호 서버 연결이 끊어졌습니다. 다시 연결해 주세요.');
      }
      ws.send(JSON.stringify({ type: 'offer', sdp: offer.sdp }));
    } catch (err) {
      shareError.textContent = '화면 공유를 시작할 수 없습니다: ' + err.message;
      cleanupCall();
      showOnly(shareCard);
    }
  }

  function onConnected() {
    checkCircle.style.animation = 'none';
    void checkCircle.offsetWidth;
    checkCircle.style.animation = '';
    showOnly(successOverlay);
    setTimeout(() => showOnly(liveOverlay), 150);
  }

  function cleanupCall() {
    if (pc) { pc.close(); pc = null; }
    if (localStream) { localStream.getTracks().forEach((t) => t.stop()); localStream = null; }
  }

  function stopShare() {
    cleanupCall();
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'stop' }));
    }
    showOnly(shareCard);
  }

  btnShare.addEventListener('click', startShare);
  btnStop.addEventListener('click', stopShare);
  btnStop2.addEventListener('click', stopShare);
})();
