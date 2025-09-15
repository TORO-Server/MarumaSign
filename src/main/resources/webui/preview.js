// URLが変更されたときにリソースを置き換えする
function detectChangeURL() {
    console.log('URL Change Detected');

    // HTMLElement取得
    const imgelem = document.getElementById('previewimg');
    const urlinput = document.getElementById("address");

    // initエラーチェック
    if (imgelem == null | urlinput == null | urlinput.value == undefined | imgelem.src == undefined) {
        console.error('Cannnot get HTMLElement')
        return;
    }

    const imgurl = urlinput.value;
    imgelem.src = imgurl;

    // 読み込み正常完了
    imgelem.onload = () => console.log(`Loaded image: ${imgurl}`);

    // 読み込み異常終了
    imgelem.onerror = () => console.error(`Cannnot get Image: ${imgurl}`);
}
