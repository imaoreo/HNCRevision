const vlsmExercises = [
  {
    "title": "Warm-up",
    "parent": "192.168.25.0/24",
    "requests": [
      [
        "LAN A",
        60
      ],
      [
        "LAN B",
        28
      ],
      [
        "LAN C",
        12
      ],
      [
        "LAN D",
        2
      ]
    ],
    "rows": [
      [
        "LAN A",
        60,
        "192.168.25.0/26",
        "255.255.255.192",
        "192.168.25.1",
        "192.168.25.62",
        "192.168.25.63",
        62
      ],
      [
        "LAN B",
        28,
        "192.168.25.64/27",
        "255.255.255.224",
        "192.168.25.65",
        "192.168.25.94",
        "192.168.25.95",
        30
      ],
      [
        "LAN C",
        12,
        "192.168.25.96/28",
        "255.255.255.240",
        "192.168.25.97",
        "192.168.25.110",
        "192.168.25.111",
        14
      ],
      [
        "LAN D",
        2,
        "192.168.25.112/30",
        "255.255.255.252",
        "192.168.25.113",
        "192.168.25.114",
        "192.168.25.115",
        2
      ]
    ]
  },
  {
    "title": "Uneven departments",
    "parent": "192.168.40.0/24",
    "requests": [
      [
        "LAN A",
        14
      ],
      [
        "LAN B",
        100
      ],
      [
        "LAN C",
        2
      ],
      [
        "LAN D",
        50
      ],
      [
        "LAN E",
        6
      ]
    ],
    "rows": [
      [
        "LAN B",
        100,
        "192.168.40.0/25",
        "255.255.255.128",
        "192.168.40.1",
        "192.168.40.126",
        "192.168.40.127",
        126
      ],
      [
        "LAN D",
        50,
        "192.168.40.128/26",
        "255.255.255.192",
        "192.168.40.129",
        "192.168.40.190",
        "192.168.40.191",
        62
      ],
      [
        "LAN A",
        14,
        "192.168.40.192/28",
        "255.255.255.240",
        "192.168.40.193",
        "192.168.40.206",
        "192.168.40.207",
        14
      ],
      [
        "LAN E",
        6,
        "192.168.40.208/29",
        "255.255.255.248",
        "192.168.40.209",
        "192.168.40.214",
        "192.168.40.215",
        6
      ],
      [
        "LAN C",
        2,
        "192.168.40.216/30",
        "255.255.255.252",
        "192.168.40.217",
        "192.168.40.218",
        "192.168.40.219",
        2
      ]
    ]
  },
  {
    "title": "Start halfway through a /24",
    "parent": "192.168.25.128/25",
    "requests": [
      [
        "LAN A",
        12
      ],
      [
        "LAN B",
        50
      ],
      [
        "LAN C",
        2
      ],
      [
        "LAN D",
        25
      ]
    ],
    "rows": [
      [
        "LAN B",
        50,
        "192.168.25.128/26",
        "255.255.255.192",
        "192.168.25.129",
        "192.168.25.190",
        "192.168.25.191",
        62
      ],
      [
        "LAN D",
        25,
        "192.168.25.192/27",
        "255.255.255.224",
        "192.168.25.193",
        "192.168.25.222",
        "192.168.25.223",
        30
      ],
      [
        "LAN A",
        12,
        "192.168.25.224/28",
        "255.255.255.240",
        "192.168.25.225",
        "192.168.25.238",
        "192.168.25.239",
        14
      ],
      [
        "LAN C",
        2,
        "192.168.25.240/30",
        "255.255.255.252",
        "192.168.25.241",
        "192.168.25.242",
        "192.168.25.243",
        2
      ]
    ]
  },
  {
    "title": "Cross an octet boundary",
    "parent": "10.10.8.0/23",
    "requests": [
      [
        "LAN A",
        62
      ],
      [
        "LAN B",
        200
      ],
      [
        "LAN C",
        100
      ],
      [
        "LAN D",
        28
      ],
      [
        "LAN E",
        2
      ]
    ],
    "rows": [
      [
        "LAN B",
        200,
        "10.10.8.0/24",
        "255.255.255.0",
        "10.10.8.1",
        "10.10.8.254",
        "10.10.8.255",
        254
      ],
      [
        "LAN C",
        100,
        "10.10.9.0/25",
        "255.255.255.128",
        "10.10.9.1",
        "10.10.9.126",
        "10.10.9.127",
        126
      ],
      [
        "LAN A",
        62,
        "10.10.9.128/26",
        "255.255.255.192",
        "10.10.9.129",
        "10.10.9.190",
        "10.10.9.191",
        62
      ],
      [
        "LAN D",
        28,
        "10.10.9.192/27",
        "255.255.255.224",
        "10.10.9.193",
        "10.10.9.222",
        "10.10.9.223",
        30
      ],
      [
        "LAN E",
        2,
        "10.10.9.224/30",
        "255.255.255.252",
        "10.10.9.225",
        "10.10.9.226",
        "10.10.9.227",
        2
      ]
    ]
  },
  {
    "title": "Small address pool",
    "parent": "172.16.5.64/26",
    "requests": [
      [
        "LAN A",
        6
      ],
      [
        "LAN B",
        28
      ],
      [
        "LAN C",
        2
      ],
      [
        "LAN D",
        12
      ]
    ],
    "rows": [
      [
        "LAN B",
        28,
        "172.16.5.64/27",
        "255.255.255.224",
        "172.16.5.65",
        "172.16.5.94",
        "172.16.5.95",
        30
      ],
      [
        "LAN D",
        12,
        "172.16.5.96/28",
        "255.255.255.240",
        "172.16.5.97",
        "172.16.5.110",
        "172.16.5.111",
        14
      ],
      [
        "LAN A",
        6,
        "172.16.5.112/29",
        "255.255.255.248",
        "172.16.5.113",
        "172.16.5.118",
        "172.16.5.119",
        6
      ],
      [
        "LAN C",
        2,
        "172.16.5.120/30",
        "255.255.255.252",
        "172.16.5.121",
        "172.16.5.122",
        "172.16.5.123",
        2
      ]
    ]
  },
  {
    "title": "Multiple equal-sized LANs",
    "parent": "10.20.30.0/24",
    "requests": [
      [
        "LAN A",
        30
      ],
      [
        "LAN B",
        60
      ],
      [
        "LAN C",
        30
      ],
      [
        "LAN D",
        14
      ],
      [
        "LAN E",
        2
      ]
    ],
    "rows": [
      [
        "LAN B",
        60,
        "10.20.30.0/26",
        "255.255.255.192",
        "10.20.30.1",
        "10.20.30.62",
        "10.20.30.63",
        62
      ],
      [
        "LAN A",
        30,
        "10.20.30.64/27",
        "255.255.255.224",
        "10.20.30.65",
        "10.20.30.94",
        "10.20.30.95",
        30
      ],
      [
        "LAN C",
        30,
        "10.20.30.96/27",
        "255.255.255.224",
        "10.20.30.97",
        "10.20.30.126",
        "10.20.30.127",
        30
      ],
      [
        "LAN D",
        14,
        "10.20.30.128/28",
        "255.255.255.240",
        "10.20.30.129",
        "10.20.30.142",
        "10.20.30.143",
        14
      ],
      [
        "LAN E",
        2,
        "10.20.30.144/30",
        "255.255.255.252",
        "10.20.30.145",
        "10.20.30.146",
        "10.20.30.147",
        2
      ]
    ]
  }
];

const exerciseHints = [
  [
    'Start with LAN A. It needs 60 usable addresses, more than any other LAN in this question.',
    'Add two reserved addresses to 60, then find the next power of two. How many host bits does that block need?',
    'A 64-address block starting at .0 ends at .63. Start the next LAN at .64, then repeat the block-size calculation.'
  ],
  [
    'Sort by host requirement before assigning addresses. LAN B comes before LAN D.',
    'LAN B needs 102 total addresses once you include the network and broadcast. Compare 64 with 128.',
    'After LAN B takes the first 128 addresses, LAN D starts at .128. Choose its block size before placing the smaller LANs.'
  ],
  [
    'Your first available address ends in .128. The lower half of the /24 is outside the parent network.',
    'LAN B needs 50 usable addresses. Six host bits give 64 total addresses, so subtract six from 32 to get its prefix.',
    'A 64-address block starting at .128 ends at .191. LAN D is next and starts at .192.'
  ],
  [
    'The /23 includes both 10.10.8.x and 10.10.9.x. Start with LAN B, which needs 200 usable addresses.',
    'The smallest block for 202 total addresses has 256 addresses. That fills the entire 10.10.8.x range.',
    'After 10.10.8.255, the next address is 10.10.9.0. Start LAN C there and size it for 100 usable hosts.'
  ],
  [
    'The /26 starts at .64 and ends at .127. Place LAN B first, then LAN D.',
    'LAN B needs 30 total addresses, which fits a 32-address block. Count .64 itself as the first address in the block.',
    'LAN B ends at .95. Start LAN D at .96 and work out how many addresses will be left for LAN A and LAN C.'
  ],
  [
    'LAN B goes first. For the two LANs that need 30 usable hosts each, allocate LAN A before LAN C.',
    'Exactly 30 usable hosts fit a 32-address block. Remember that the network and broadcast use the other two addresses.',
    'After LAN B’s 64-address block, LAN A starts at .64. Add its block size to find LAN C’s network address.'
  ]
];

const hintButton = document.getElementById('vlsmHint');
const hintList = document.getElementById('vlsmHints');

hintButton.addEventListener('click', () => {
  const hints = exerciseHints[exerciseSelect.value];
  const nextHint = hintList.childElementCount;
  if (nextHint >= hints.length) return;

  const item = document.createElement('li');
  item.textContent = hints[nextHint];
  hintList.append(item);
  hintButton.disabled = hintList.childElementCount === hints.length;
  hintButton.textContent = hintButton.disabled
    ? 'All 3 hints shown'
    : `Show hint (${nextHint + 2} of 3)`;
});

const exerciseSelect = document.getElementById('vlsmQuestion');
const working = document.getElementById('vlsmWorking');
const reveal = document.getElementById('vlsmReveal');
const solution = document.getElementById('vlsmSolution');
const workingKey = 'hnc-vlsm-working-v1';
const drafts = {};
try {
  Object.assign(drafts, JSON.parse(localStorage.getItem(workingKey) || '{}'));
} catch {
  // Working remains available in memory if storage is unavailable.
}

vlsmExercises.forEach((exercise, index) => {
  exerciseSelect.add(new Option(`${index + 1}. ${exercise.title}`, index));
});

function showVlsmExercise() {
  const exercise = vlsmExercises[exerciseSelect.value];
  document.getElementById('vlsmPrompt').textContent = `Allocate subnets within ${exercise.parent}.`;
  document.getElementById('vlsmRequests').innerHTML = exercise.requests.map(
    ([lan, hosts]) => `<li>${lan} needs ${hosts} usable addresses</li>`
  ).join('');
  working.value = drafts[exerciseSelect.value] || '';
  hintList.replaceChildren();
  hintButton.disabled = false;
  hintButton.textContent = 'Show hint (1 of 3)';
  solution.replaceChildren();
  solution.classList.add('hidden');
  reveal.textContent = 'Reveal answer';
  reveal.setAttribute('aria-expanded', 'false');
}

exerciseSelect.addEventListener('change', showVlsmExercise);
working.addEventListener('input', () => {
  drafts[exerciseSelect.value] = working.value;
  try {
    localStorage.setItem(workingKey, JSON.stringify(drafts));
  } catch {
    // Keep the current session's drafts available.
  }
});

reveal.addEventListener('click', () => {
  const visible = solution.classList.toggle('hidden') === false;
  reveal.setAttribute('aria-expanded', String(visible));
  reveal.textContent = visible ? 'Hide answer' : 'Reveal answer';
  if (!visible) return;
  const exercise = vlsmExercises[exerciseSelect.value];
  const steps = exercise.rows.map(([lan, hosts, network, , , , broadcast, capacity]) =>
    `<li>${lan} needs ${hosts} hosts. Allow two reserved addresses, then round ${hosts + 2} up to ${capacity + 2} addresses. Allocate ${network}. The next block starts immediately after ${broadcast}.</li>`
  ).join('');
  const headings = ['LAN', 'Hosts needed', 'Network / prefix', 'Subnet mask', 'First usable', 'Last usable', 'Broadcast', 'Usable capacity'];
  const rows = exercise.rows.map(row =>
    `<tr>${row.map(value => `<td>${value}</td>`).join('')}</tr>`
  ).join('');
  solution.innerHTML = `<h3>Worked solution</h3><ol>${steps}</ol>
    <div class="vlsm-table" tabindex="0" role="region" aria-label="Subnet allocation table">
      <table><thead><tr>${headings.map(label => `<th scope="col">${label}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table>
    </div>`;
});

showVlsmExercise();
