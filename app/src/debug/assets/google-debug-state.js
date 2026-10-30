(() => {
  if(location.origin!=='https://www.google.com'||window.top!==window||!/^\/android\/find(?:\/u\/\d+)?\/?$/.test(location.pathname))return;
  let serial='',scheduled=false;
  const observedControlIcons=new Set();
  const observedFieldClasses=new Set();
  let observedImei=false,observedRegistration=false,observedPixel9=false;
  const visible=e=>!!e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden';
  const inspect=()=>{
    scheduled=false;
    const controls=[...document.querySelectorAll('button,[role=button]')];
    for(const e of controls.filter(visible)) {
      const icon=e.textContent.trim();
      if(/^[a-z_]{2,40}$/.test(icon))observedControlIcons.add(icon);
    }
    const text=document.body?.innerText||'';
    observedImei ||= /IMEI\s*[:：]/i.test(text);
    observedRegistration ||= /初回登録日|First registered/i.test(text);
    observedPixel9 ||= /Pixel\s*9/i.test(text);
    for(const e of document.querySelectorAll('div,span')) {
      if(!e.childElementCount&&visible(e)&&/^(?:IMEI|MEID|初回登録日|First registered)\s*[:：]/i.test(e.textContent.trim())) observedFieldClasses.add(e.className+' / '+e.parentElement?.className);
    }
    const data={
      ready:document.readyState,reader:!!window.FindBridgeGoogleReader,
      indexedButtons:document.querySelectorAll('[role=button][data-index]').length,
      allIndexed:document.querySelectorAll('[data-index]').length,
      buttons:controls.length,visibleButtons:controls.filter(visible).length,
      observedControlIcons:[...observedControlIcons],
      observedImei,observedRegistration,observedPixel9,observedFieldClasses:[...observedFieldClasses],
      basicInfoNodes:document.querySelectorAll('.Z3br3c').length,
      imeiVisible:/IMEI\s*[:：]/i.test(text),
      registrationVisible:/初回登録日|First registered/i.test(text),
      pixel9ModelVisible:/Pixel\s*9/i.test(text),
      informationFieldNodes:[...document.querySelectorAll('div,span')].filter(e=>!e.childElementCount&&visible(e)&&/^(?:IMEI|MEID|初回登録日|First registered)\s*[:：]/i.test(e.textContent.trim())).slice(0,8).map(e=>({tag:e.tagName,className:e.className,parentClass:e.parentElement?.className})),
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
