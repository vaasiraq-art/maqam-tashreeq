/* Web MIDI API polyfill for Android WebView, backed by android.media.midi.
   Android WebView ships no Web MIDI (only Chrome does), so without this the
   page finds zero devices. Injected after page load by MainActivity. */
(function () {
  "use strict";
  if (typeof AndroidMIDI === "undefined") return;          // running in a normal browser
  if (navigator.requestMIDIAccess && !window.__forceShim) return;

  var inputs = new Map(), outputs = new Map(), access = null;

  function Port(d, type) {
    this.id = d.id;
    this.name = d.name || "MIDI";
    this.manufacturer = d.manufacturer || "";
    this.version = d.version || "1";
    this.type = type;
    this.state = "connected";
    this.connection = "closed";
    this.onmidimessage = null;
    this.onstatechange = null;
  }
  Port.prototype.open = function () {
    var self = this;
    try { AndroidMIDI.openPort(this.id); } catch (e) {}
    this.connection = "open";
    return Promise.resolve(self);
  };
  Port.prototype.close = function () { this.connection = "closed"; return Promise.resolve(this); };
  Port.prototype.send = function (data) {
    var a = [];
    for (var i = 0; i < data.length; i++) a.push(data[i] & 0xFF);
    try { AndroidMIDI.send(this.id, JSON.stringify(a)); }
    catch (e) { console.error("midi send failed", e); }
  };
  Port.prototype.clear = function () {};
  Port.prototype.addEventListener = function (t, fn) { if (t === "midimessage") this.onmidimessage = fn; };

  function rebuild() {
    var data;
    try { data = JSON.parse(AndroidMIDI.listPorts()); }
    catch (e) { return; }
    var seen = {};

    function sync(list, map, type) {
      list.forEach(function (d) {
        seen[d.id] = true;
        if (!map.has(d.id)) {
          var p = new Port(d, type);
          map.set(d.id, p);
          p.open();                                   // Android needs an explicit open
          fire(p, "connected");
        }
      });
    }
    sync(data.inputs || [], inputs, "input");
    sync(data.outputs || [], outputs, "output");

    [inputs, outputs].forEach(function (map) {
      map.forEach(function (p, id) {
        if (!seen[id]) { p.state = "disconnected"; map.delete(id); fire(p, "disconnected"); }
      });
    });
  }

  function fire(port, state) {
    if (!access) return;
    var ev = { port: port, target: access, type: "statechange" };
    if (typeof access.onstatechange === "function") { try { access.onstatechange(ev); } catch (e) {} }
  }

  /* called from Kotlin when bytes arrive */
  window.__androidMidiRx = function (portId, bytes) {
    var p = inputs.get(portId);
    if (!p || typeof p.onmidimessage !== "function") return;
    try {
      p.onmidimessage({ data: new Uint8Array(bytes), receivedTime: performance.now(), target: p });
    } catch (e) { console.error(e); }
  };

  /* called from Kotlin when a device is plugged in or removed */
  window.__androidMidiStateChange = function () { rebuild(); };

  navigator.requestMIDIAccess = function (opts) {
    return new Promise(function (resolve, reject) {
      try {
        if (!AndroidMIDI.hasMidiFeature()) {
          reject(new Error("This Android device does not report MIDI support"));
          return;
        }
      } catch (e) {}
      access = {
        inputs: inputs,
        outputs: outputs,
        sysexEnabled: !!(opts && opts.sysex),   // native bridge passes raw bytes, SysEx included
        onstatechange: null
      };
      rebuild();
      setTimeout(rebuild, 600);                 // some USB devices enumerate a moment late
      resolve(access);
    });
  };

  window.__webMidiShim = "android-native";
})();
