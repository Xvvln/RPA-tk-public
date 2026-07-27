/**
 * TikTok 46.1.3 popup lab (Frida).
 * - Discovery hooks: Dialog / PopupManager / EmailPopUpFragment / androguard xrefs
 * - Activate: soft-ask AlertDialog with unpack CTAs, or live EmailPopUpFragment.show
 */
'use strict';

const SOFT = ['以后再说', '稍后再说', 'Not now', 'Maybe later', '跳过', 'Skip', '我知道了', 'Got it'];
const PRIMARY = ['开启', '允许', 'Allow', 'Continue', 'Confirm', '同意', '更新'];

/** From tools/popup-hook/candidates/from_unpack_46.1.3.json (jadx + androguard) */
const XREF_HOOKS = [
  { cls: 'X.10Ao', methods: ['LIZ', 'onCanceled'] },
  { cls: 'X.11Yl', methods: ['LJJLIL'] },
  { cls: 'Y.ARunnableS97S0200000_29', methods: ['LIZ$9'] },
  { cls: 'Y.AcS504S0100000_17', methods: ['LIZ$64'] },
  { cls: 'com.ss.android.ugc.aweme.account.login.email.EmailPopUpFragment', methods: ['show'] },
  { cls: 'com.ss.android.ugc.aweme.account.prompt.AccountShowConfirmPromptServiceImpl', methods: ['LIZ', 'LIZJ'] },
  { cls: 'com.ss.android.ugc.aweme.account.experiment.prompt.ShowPromptToConfirmDynamicDataExperiment', methods: ['LIZ'] },
  { cls: 'com.bytedance.poplayer.core.PopupManager', methods: null } // all show-like
];

function log(msg) {
  console.log('[popup-hook] ' + msg);
}

function dumpStack(tag) {
  try {
    const Exception = Java.use('java.lang.Exception');
    const Log = Java.use('android.util.Log');
    const s = Log.getStackTraceString(Exception.$new(tag));
    log('stack ' + tag + ':\n' + s.split('\n').slice(0, 16).join('\n'));
  } catch (e) {
    log('stack failed: ' + e);
  }
}

function currentActivity() {
  let activity = null;
  Java.choose('android.app.Activity', {
    onMatch(instance) {
      try {
        if (!instance.isFinishing()) activity = instance;
      } catch (e) {}
    },
    onComplete() {}
  });
  return activity;
}

function activateSoftAskDialog(title, message) {
  const ActivityThread = Java.use('android.app.ActivityThread');
  const app = ActivityThread.currentApplication();
  const activity = currentActivity();
  const host = activity !== null ? activity : app.getApplicationContext();

  Java.scheduleOnMainThread(function () {
    try {
      const Builder = Java.use('android.app.AlertDialog$Builder');
      const b = Builder.$new(host);
      b.setTitle(title || 'Lab soft-ask (unpack CTAs)');
      b.setMessage(
        message ||
          'From 46.1.3 string pool. Soft: 以后再说 / Not now. Primary: 开启 / Allow (RPA must not click).'
      );
      b.setPositiveButton(PRIMARY[0], null);
      b.setNegativeButton(SOFT[0], null);
      b.setNeutralButton(SOFT[2], null);
      b.setCancelable(true);
      b.create().show();
      log('activated soft-ask AlertDialog');
    } catch (e) {
      log('activateSoftAskDialog failed: ' + e);
    }
  });
  return true;
}

function tryEmailPopupShow() {
  try {
    const Cls = Java.use('com.ss.android.ugc.aweme.account.login.email.EmailPopUpFragment');
    let found = false;
    Java.choose('com.ss.android.ugc.aweme.account.login.email.EmailPopUpFragment', {
      onMatch(instance) {
        found = true;
        try {
          instance.show();
          log('EmailPopUpFragment.show() on live instance OK');
        } catch (e) {
          log('EmailPopUpFragment.show error: ' + e);
        }
      },
      onComplete() {}
    });
    if (!found) log('no live EmailPopUpFragment instance');
    return found;
  } catch (e) {
    log('EmailPopUpFragment unavailable: ' + e);
    return false;
  }
}

function hookOverloads(clazz, methodName) {
  if (!clazz[methodName] || !clazz[methodName].overloads) return 0;
  let n = 0;
  clazz[methodName].overloads.forEach(function (ov) {
    ov.implementation = function () {
      log(clazz.$className + '.' + methodName + ' argc=' + arguments.length);
      dumpStack(clazz.$className + '.' + methodName);
      return ov.apply(this, arguments);
    };
    n++;
  });
  return n;
}

function hookClassMethods(className, methods) {
  try {
    const Cls = Java.use(className);
    if (methods == null) {
      const declared = Cls.class.getDeclaredMethods();
      let total = 0;
      for (let i = 0; i < declared.length; i++) {
        const name = declared[i].getName();
        const lower = name.toLowerCase();
        if (
          lower.indexOf('show') >= 0 ||
          lower.indexOf('enqueue') >= 0 ||
          lower.indexOf('push') >= 0 ||
          lower.indexOf('popup') >= 0 ||
          name === 'LIZ' ||
          name === 'LIZIZ' ||
          name === 'LIZJ'
        ) {
          total += hookOverloads(Cls, name);
        }
      }
      log('hooked ' + className + ' show-like methods count~' + total);
      return;
    }
    methods.forEach(function (m) {
      try {
        const c = hookOverloads(Cls, m);
        if (c) log('hooked ' + className + '.' + m + ' overloads=' + c);
      } catch (e) {
        log('skip ' + className + '.' + m + ': ' + e);
      }
    });
  } catch (e) {
    log('class missing ' + className + ': ' + e);
  }
}

function hookFrameworkDialogs() {
  try {
    const Dialog = Java.use('android.app.Dialog');
    Dialog.show.implementation = function () {
      try {
        log('Dialog.show() ' + this.getClass().getName());
        dumpStack('Dialog.show');
      } catch (e) {}
      return this.show();
    };
    log('hooked android.app.Dialog.show');
  } catch (e) {
    log('Dialog hook failed: ' + e);
  }
}

rpc.exports = {
  ping() {
    return 'pong';
  },
  activateSoftask(title, message) {
    Java.perform(function () {
      activateSoftAskDialog(title, message);
    });
    return true;
  },
  activateEmail() {
    let ok = false;
    Java.perform(function () {
      ok = tryEmailPopupShow();
    });
    return ok;
  },
  activity() {
    let name = '';
    Java.perform(function () {
      const a = currentActivity();
      name = a ? a.getClass().getName() : '';
    });
    return name;
  }
};

Java.perform(function () {
  log('Java.perform ready pkg lab hooks');
  hookFrameworkDialogs();
  XREF_HOOKS.forEach(function (t) {
    hookClassMethods(t.cls, t.methods);
  });
  log('hooks installed; rpc: activateSoftask / activateEmail / activity / ping');
});
