const form=document.querySelector('#login'),button=document.querySelector('#submit'),error=document.querySelector('#error');
form.addEventListener('submit',async event=>{
 event.preventDefault();button.disabled=true;error.textContent='';
 try{
  const response=await fetch('/api/auth/browser-login',{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify({email:document.querySelector('#account').value,password:document.querySelector('#password').value})});
  if(!response.ok)throw Error(response.status===401?'账号或密码不正确':'无法登录，请稍后重试');
  const member=await response.json();
  if(member.mustChangePassword)throw Error('请先在账号设置中完成首次密码修改');
  document.querySelector('#password').value='';window.location.assign('/workdsh/enter');
 }catch(reason){error.textContent=reason.message;button.disabled=false;}
});
