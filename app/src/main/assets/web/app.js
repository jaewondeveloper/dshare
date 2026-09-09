(function () {
  const joinCard = document.getElementById('joinCard');
  const shareCard = document.getElementById('shareCard');
  const codeInput = document.getElementById('codeInput');
  const joinError = document.getElementById('joinError');
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

  function showOnly(el) {
    [joinCard, shareCard, connectingOverlay, successOverlay, liveOverlay].forEach((e) => {
      if (!e) return;
      e.hidden = e !== el;
    });
  }

  function connectSocket(onOpen) {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.onopen = () => onOpen && onOpen();
    ws.onmessage = (evt) => handleServerMessage(JSON.parse(evt.data));
    ws.onclose = () => { ws = null; };
  }

  function handleServerMessage(msg) {
    switch (msg.type) {
      case 'joined':
        joinError.textContent = '';
        showOnly(shareCard);
        break;
      case 'error':
        joinError.textContent = msg.message || '연결에 실패했습니다.';
        break;
      case 'answer':
        pc && pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
        break;
      case 'ice':
        if (pc && msg.candidate) {
          pc.addIceCandidate({ candidate: msg.candidate, sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex });
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
    connectSocket(() => ws.send(JSON.stringify({ type: 'join', code })));
  });

  async function startShare() {
    showOnly(connectingOverlay);
    try {
      let stream;
      try {
        stream = await navigator.mediaDevices.getDisplayMedia({
          video: { width: { ideal: 2560 }, height: { ideal: 1440 }, frameRate: { ideal: 60 } },
          audio: true
        });
      } catch (e) {
        stream = await navigator.mediaDevices.getDisplayMedia({
          video: { width: { ideal: 2560 }, height: { ideal: 1440 }, frameRate: { ideal: 60 } },
          audio: false
        });
      }
      localStream = stream;

      pc = new RTCPeerConnection({ iceServers: [] });
      stream.getTracks().forEach((track) => {
        track.addEventListener('ended', () => stopShare());
        pc.addTrack(track, stream);
      });

      const videoSender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
      if (videoSender) {
        const params = videoSender.getParameters();
        if (!params.encodings) params.encodings = [{}];
        params.encodings[0].maxBitrate = 12_000_000;
        params.degradationPreference = 'maintain-resolution';
        try { await videoSender.setParameters(params); } catch (e) { /* best effort */ }
      }

      pc.onicecandidate = (evt) => {
        if (evt.candidate) {
          ws.send(JSON.stringify({
            type: 'ice',
            candidate: evt.candidate.candidate,
            sdpMid: evt.candidate.sdpMid,
            sdpMLineIndex: evt.candidate.sdpMLineIndex
          }));
        }
      };

      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'connected') {
          onConnected();
        } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') {
          stopShare();
        }
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      ws.send(JSON.stringify({ type: 'offer', sdp: offer.sdp }));
    } catch (err) {
      joinError.textContent = '화면 공유를 시작할 수 없습니다: ' + err.message;
      showOnly(shareCard);
    }
  }

  function onConnected() {
    // restart jelly-check animation each time it appears
    checkCircle.style.animation = 'none';
    void checkCircle.offsetWidth;
    checkCircle.style.animation = '';
    showOnly(successOverlay);
    setTimeout(() => showOnly(liveOverlay), 1000);
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
