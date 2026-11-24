-- Run by main.c at start-up. Edit and run again: no need to rebuild for Lua changes.

local function greet(name)
  return ("Hello, %s! Lua %s is running inside C."):format(name, _VERSION:match("%d+%.%d+"))
end

print(greet("@PROJECT_NAME@"))

-- A function the prompt can call afterwards: primes(50)
function primes(limit)
  local list = {}
  for n = 2, limit do
    local prime = true
    for d = 2, math.floor(math.sqrt(n)) do
      if n % d == 0 then prime = false break end
    end
    if prime then list[#list + 1] = n end
  end
  return table.concat(list, " ")
end
