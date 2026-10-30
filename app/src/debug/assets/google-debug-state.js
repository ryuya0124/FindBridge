(() => {
  if(location.origin!=='https://www.google.com'||window.top!==window||!/^\/android\/find(?:\/u\/\d+)?\/?$/.test(location.pathname))return;
  let serial='',scheduled=false;
  const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden';
  const inspect=()=>{
    scheduled=false;
    const controls=[...document.querySelectorAll('button,[role=button]')];
    const text=document.body?.innerText||'';
    const data={
      ready:document.readyState,reader:!!window.FindBridgeGoogleReader,
      indexedButtons:document.querySelectorAll('[role=button][data-index]').length,
      allIndexed:document.querySelectorAll('[data-index]').length,
      buttons:controls.length,visibleButtons:controls.filter(visible).length,
      potentialNodes:controls.filter(e=>e.querySelector('img')).slice(0,12).map(e=>({tag:e.tagName,role:e.getAttribute('role'),visible:visible(e),attributes:e.getAttributeNames()})),
      attributeCounts:Object.fromEntries(['data-device-id','data-device-index','data-row-id','data-location-lat','data-slot-id','data-email'].map(a=>[a,document.querySelectorAll('['+a+']').length])),
      dialogs:[...document.querySelectorAll('[role=dialog],[aria-modal=true]')].filter(visible).map(e=>({pin:/PIN|暗証番号/.test(e.innerText),password:/password|パスワード/i.test(e.innerText)})),
      publicStates:{noDevices:/No devices found|デバイスが見つかりません|デバイスがありません/i.test(text),loading:/Loading|読み込み|ロード中/i.test(text),signIn:/Sign in|ログイン/i.test(text),unavailable:/Something went wrong|問題が発生|エラーが発生/i.test(text),consent:/Accept|同意する/i.test(text)},
      avatarHasEmail:!!document.querySelector('[aria-label*="@"][role=button],button[aria-label*="@"],a[aria-label*="@"]')
    };
    const next=JSON.stringify(data);if(next!==serial){serial=next;FindBridgeGoogleDebug.postMessage(next);}
  };
  const schedule=()=>{if(!scheduled){scheduled=true;queueMicrotask(inspect);}};
  new MutationObserver(schedule).observe(document,{subtree:true,childList:true,attributes:true,characterData:true});
  for(const event of ['DOMContentLoaded','load','pageshow'])addEventListener(event,schedule);
  schedule();
})();
