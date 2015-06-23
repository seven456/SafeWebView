javascript: (function(b) {
    console.log("Android initialization begin");
    var a = {
        queue: [],
        callback: function() {
            var d = Array.prototype.slice.call(arguments, 0);
            var c = d.shift();
            var e = d.shift();
            this.queue[c].apply(this, d);
            if (!e) {
                deletethis.queue[c]
            }
        }
    };
    a.delayJsCallBack = a.equals = a.getClass = a.hashCode = a.notify = a.notifyAll = a.testLossTime = a.toString = a.toast = a.wait = a.wait = a.wait = function() {
        var f = Array.prototype.slice.call(arguments, 0);
        if (f.length < 1) {
            throw "Android call error, message:miss method name"
        }
        var e = [];
        for (var h = 1; h < f.length; h++) {
            var c = f[h];
            var j = typeof c;
            e[e.length] = j;
            if (j == "function") {
                var d = a.queue.length;
                a.queue[d] = c;
                f[h] = d
            }
        }
        var g = JSON.parse(prompt('SafeWebView: ' + JSON.stringify({
            method: f.shift(),
            types: e,
            args: f
        })));
        if (g.code != 200) {
            throw "Android call error, code:" + g.code + ", message:" + g.result
        }
        returng.result
    };
    Object.getOwnPropertyNames(a).forEach(function(d) {
        var c = a[d];
        if (typeof c === "function" && d !== "callback") {
            a[d] = function() {
                returnc.apply(a, [d].concat(Array.prototype.slice.call(arguments, 0)))
            }
        }
    });
    b.Android = a;
    console.log("Android initialization end")
})(window);