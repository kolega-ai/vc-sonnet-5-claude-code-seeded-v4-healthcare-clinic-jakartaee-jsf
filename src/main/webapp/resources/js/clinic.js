/* Progressive enhancement only: the portal is fully usable without it. */
(function () {
  "use strict";

  function toggleNotifications(event) {
    event.preventDefault();
    var panel = document.getElementById("notification-panel");
    if (!panel) {
      return;
    }
    var open = panel.hasAttribute("hidden");
    if (open) {
      panel.removeAttribute("hidden");
    } else {
      panel.setAttribute("hidden", "hidden");
    }
    event.currentTarget.setAttribute("aria-expanded", String(open));
  }

  function closeOnOutsideClick(event) {
    var panel = document.getElementById("notification-panel");
    var trigger = document.getElementById("notification-trigger");
    if (!panel || panel.hasAttribute("hidden")) {
      return;
    }
    if (!panel.contains(event.target) && trigger && !trigger.contains(event.target)) {
      panel.setAttribute("hidden", "hidden");
      trigger.setAttribute("aria-expanded", "false");
    }
  }

  function wireTabs() {
    var groups = document.querySelectorAll("[data-tabs]");
    Array.prototype.forEach.call(groups, function (group) {
      var buttons = group.querySelectorAll("[data-tab-target]");
      Array.prototype.forEach.call(buttons, function (button) {
        button.addEventListener("click", function () {
          var targetId = button.getAttribute("data-tab-target");
          Array.prototype.forEach.call(buttons, function (other) {
            other.classList.toggle("is-active", other === button);
            other.setAttribute("aria-selected", String(other === button));
            var panel = document.getElementById(other.getAttribute("data-tab-target"));
            if (panel) {
              if (other === button) {
                panel.removeAttribute("hidden");
              } else {
                panel.setAttribute("hidden", "hidden");
              }
            }
          });
          if (window.history && window.history.replaceState) {
            window.history.replaceState(null, "", "#" + targetId);
          }
        });
      });
      var requested = window.location.hash.replace("#", "");
      if (requested) {
        var match = group.querySelector('[data-tab-target="' + CSS.escape(requested) + '"]');
        if (match) {
          match.click();
        }
      }
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var trigger = document.getElementById("notification-trigger");
    if (trigger) {
      trigger.addEventListener("click", toggleNotifications);
    }
    document.addEventListener("click", closeOnOutsideClick);
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") {
        var panel = document.getElementById("notification-panel");
        if (panel) {
          panel.setAttribute("hidden", "hidden");
        }
      }
    });
    wireTabs();
  });
})();
